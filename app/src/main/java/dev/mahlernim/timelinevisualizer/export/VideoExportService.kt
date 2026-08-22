package dev.mahlernim.timelinevisualizer.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.mahlernim.timelinevisualizer.MainActivity
import dev.mahlernim.timelinevisualizer.R
import dev.mahlernim.timelinevisualizer.videos.GeneratedMediaRepository
import dev.mahlernim.timelinevisualizer.videos.VideoMedia
import dev.mahlernim.timelinevisualizer.videos.VideoRecord
import dev.mahlernim.timelinevisualizer.videos.VideoStore
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.render.TimelineAnimation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

class VideoExportService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private lateinit var requestStore: VideoExportRequestStore
    private var exportJob: Job? = null
    private var lastNotificationAt = 0L
    private var lastNotificationPhase: ExportPhase? = null
    private val etaEstimator = ExportEtaEstimator()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        requestStore = VideoExportRequestStore(applicationContext)
        VideoExportCoordinator.restore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelExport(startId)
            else -> startExport(startId)
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        exportJob?.cancel(CancellationException("Media processing time limit reached"))
        stopSelf(startId)
    }

    private fun startExport(startId: Int) {
        if (exportJob?.isActive == true) return
        startVideoForeground(buildStartingNotification())
        exportJob = serviceScope.launch {
            val request = withContext(Dispatchers.IO) { requestStore.load() }
            if (request == null) {
                VideoExportStateStore(applicationContext).load().outputUri
                    ?.toUri()
                    ?.let { GeneratedMediaRepository(applicationContext).discard(it) }
                finishWithFailure(null, getString(R.string.video_request_unavailable), startId)
                return@launch
            }
            runExport(request, startId)
        }
    }

    private suspend fun runExport(request: VideoExportRequest, startId: Int) {
        val uri = request.outputUri.toUri()
        val startedAtMillis = System.currentTimeMillis()
        etaEstimator.reset()
        publish(
            VideoExportSnapshot(
                status = VideoExportStatus.RUNNING,
                progress = ExportProgress(0f, ExportPhase.PREPARING_MAP, 0, 0),
                startedAtMillis = startedAtMillis,
                outputUri = request.outputUri,
                title = request.title,
            ),
        )
        try {
            val overview = Mp4Exporter(contentResolver, TileRepository(applicationContext)).export(
                uri,
                request.journey,
                request.title,
                request.durationSeconds,
                request.renderText,
                request.cameraSettings,
            ) { progress ->
                val snapshot = VideoExportSnapshot(
                    status = VideoExportStatus.RUNNING,
                    progress = progress,
                    startedAtMillis = startedAtMillis,
                    outputUri = request.outputUri,
                    title = request.title,
                )
                val now = SystemClock.elapsedRealtime()
                val shouldNotify = now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS ||
                    progress.phase != lastNotificationPhase || progress.phase == ExportPhase.COMPLETE
                VideoExportCoordinator.publish(applicationContext, snapshot, persist = shouldNotify)
                if (shouldNotify) {
                    lastNotificationAt = now
                    lastNotificationPhase = progress.phase
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildProgressNotification(snapshot),
                    )
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    VideoMedia(applicationContext).saveGeneratedOverview(uri, overview)
                }
            } finally {
                overview.recycle()
            }
            GeneratedMediaRepository(applicationContext).finalizeVideo(uri)
            persistUriAccess(uri)
            registerVideo(uri, request)
            requestStore.clear()
            val completed = VideoExportSnapshot(
                status = VideoExportStatus.COMPLETE,
                progress = ExportProgress(1f, ExportPhase.COMPLETE, 1, 1),
                startedAtMillis = startedAtMillis,
                outputUri = request.outputUri,
                title = request.title,
            )
            publish(completed)
            finishForeground()
            notificationManager.notify(NOTIFICATION_ID, buildCompletedNotification(uri, request.title))
        } catch (_: CancellationException) {
            GeneratedMediaRepository(applicationContext).discard(uri)
            requestStore.clear()
            publish(
                VideoExportSnapshot(
                    status = VideoExportStatus.CANCELLED,
                    startedAtMillis = startedAtMillis,
                    outputUri = request.outputUri,
                    title = request.title,
                ),
            )
            finishForeground()
            notificationManager.notify(NOTIFICATION_ID, buildCancelledNotification())
        } catch (error: Throwable) {
            Log.e(TAG, "Video export failed", error)
            GeneratedMediaRepository(applicationContext).discard(uri)
            finishWithFailure(request, failureMessage(error), startId)
            return
        } finally {
            exportJob = null
            stopSelf(startId)
        }
    }

    private fun failureMessage(error: Throwable): String = when (error) {
        is UnsupportedVideoFormatException -> error.reason.describe(this, error.format)
        is MapTilePreparationException -> getString(R.string.map_tiles_unavailable)
        else -> getString(R.string.video_export_failed)
    }

    private fun cancelExport(startId: Int) {
        val active = exportJob
        if (active?.isActive == true) {
            active.cancel()
            return
        }
        serviceScope.launch {
            val request = withContext(Dispatchers.IO) { requestStore.load() }
            request?.let { GeneratedMediaRepository(applicationContext).discard(it.outputUri.toUri()) }
            requestStore.clear()
            publish(
                VideoExportSnapshot(
                    status = VideoExportStatus.CANCELLED,
                    outputUri = request?.outputUri,
                    title = request?.title,
                ),
            )
            finishForeground()
            notificationManager.notify(NOTIFICATION_ID, buildCancelledNotification())
            stopSelf(startId)
        }
    }

    private fun finishWithFailure(request: VideoExportRequest?, message: String, startId: Int) {
        if (request == null) requestStore.clear()
        publish(
            VideoExportSnapshot(
                status = VideoExportStatus.FAILED,
                outputUri = request?.outputUri,
                title = request?.title,
                errorMessage = message,
            ),
        )
        finishForeground()
        notificationManager.notify(NOTIFICATION_ID, buildFailedNotification(message))
        exportJob = null
        stopSelf(startId)
    }

    private fun publish(snapshot: VideoExportSnapshot) {
        VideoExportCoordinator.publish(applicationContext, snapshot)
    }

    private fun finishForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun startVideoForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceTypeForApi(Build.VERSION.SDK_INT),
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun registerVideo(uri: Uri, request: VideoExportRequest) {
        val metadata = withContext(Dispatchers.IO) {
            runCatching { VideoMedia(applicationContext).inspect(uri) }.getOrNull()
        }
        VideoStore(applicationContext).upsert(
            VideoRecord(
                uri = uri.toString(),
                title = request.title,
                fileName = metadata?.fileName ?: "${request.title}.mp4",
                createdAtMillis = System.currentTimeMillis(),
                durationSeconds = metadata?.durationSeconds?.takeIf { it > 0 }
                    ?: ceil(TimelineAnimation.totalDurationSeconds(request.durationSeconds).toDouble()).toInt(),
                startYear = request.period.startYear,
                startMonth = request.period.startMonth,
                endYear = request.period.endYear,
                endMonth = request.period.endMonth,
            ),
        )
    }

    private fun persistUriAccess(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun buildStartingNotification(): Notification = notificationBuilder()
        .setContentTitle(getString(R.string.creating_video_notification_title))
        .setContentText(getString(R.string.starting_video_creation))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .addAction(0, getString(R.string.cancel), cancelPendingIntent())
        .build()

    private fun buildProgressNotification(snapshot: VideoExportSnapshot): Notification {
        val progress = snapshot.progress ?: return buildStartingNotification()
        val text = progressText(progress)
        return notificationBuilder()
            .setContentTitle(getString(R.string.creating_video_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(1000, (progress.fraction * 1000).toInt().coerceIn(0, 1000), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent())
            .build()
    }

    private fun buildCompletedNotification(uri: Uri, title: String): Notification {
        val watch = MainActivity.playbackIntent(this, uri)
        val share = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(getString(R.string.timeline_video), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_travel_video))
        return notificationBuilder()
            .setContentTitle(getString(R.string.video_ready))
            .setContentText(title)
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .addAction(0, getString(R.string.watch), activityPendingIntent(watch, WATCH_REQUEST_CODE))
            .addAction(0, getString(R.string.share), activityPendingIntent(share, SHARE_REQUEST_CODE))
            .build()
    }

    private fun buildCancelledNotification(): Notification = notificationBuilder()
        .setContentTitle(getString(R.string.video_creation_cancelled))
        .setAutoCancel(true)
        .setOngoing(false)
        .setProgress(0, 0, false)
        .build()

    private fun buildFailedNotification(message: String): Notification = notificationBuilder()
        .setContentTitle(getString(R.string.video_export_failed))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setAutoCancel(true)
        .setOngoing(false)
        .setProgress(0, 0, false)
        .build()

    private fun notificationBuilder(): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentIntent(openAppPendingIntent())
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun progressText(progress: ExportProgress): String {
        val base = when (progress.phase) {
            ExportPhase.PREPARING_MAP -> getString(
                R.string.preparing_map_progress,
                progress.completed,
                progress.total,
            )
            ExportPhase.CREATING_VIDEO -> getString(
                R.string.creating_video_progress,
                (progress.completed * 100f / progress.total.coerceAtLeast(1)).toInt(),
            )
            ExportPhase.FINISHING_VIDEO -> getString(
                R.string.finishing_video_progress,
                (progress.completed * 100f / progress.total.coerceAtLeast(1)).toInt(),
            )
            ExportPhase.COMPLETE -> getString(R.string.video_saved)
        }
        val remaining = etaEstimator.estimateRemainingSeconds(progress, SystemClock.elapsedRealtime())
        return if (remaining == null) base else getString(
            R.string.progress_with_eta,
            base,
            formatRemainingTime(remaining),
        )
    }

    private fun formatRemainingTime(seconds: Int): String = if (seconds < 90) {
        resources.getQuantityString(
            R.plurals.remaining_seconds,
            seconds.coerceAtLeast(1),
            seconds.coerceAtLeast(1),
        )
    } else {
        val minutes = ceil(seconds / 60.0).toInt()
        resources.getQuantityString(R.plurals.remaining_minutes, minutes, minutes)
    }

    private fun openAppPendingIntent(): PendingIntent = activityPendingIntent(
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        OPEN_APP_REQUEST_CODE,
    )

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        CANCEL_REQUEST_CODE,
        Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun activityPendingIntent(intent: Intent, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.video_creation_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.video_creation_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val ACTION_START = "dev.mahlernim.timelinevisualizer.action.START_EXPORT"
        private const val ACTION_CANCEL = "dev.mahlernim.timelinevisualizer.action.CANCEL_EXPORT"
        private const val CHANNEL_ID = "video_creation"
        private const val NOTIFICATION_ID = 4102
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 500L
        private const val OPEN_APP_REQUEST_CODE = 4103
        private const val CANCEL_REQUEST_CODE = 4104
        private const val WATCH_REQUEST_CODE = 4105
        private const val SHARE_REQUEST_CODE = 4106
        private const val TAG = "TimelineExport"

        internal fun foregroundServiceTypeForApi(apiLevel: Int): Int =
            if (apiLevel >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoExportService::class.java).setAction(ACTION_START),
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, VideoExportService::class.java).setAction(ACTION_CANCEL))
        }

        fun resumeIfNeeded(context: Context) {
            val snapshot = VideoExportStateStore(context).load()
            if (snapshot.status == VideoExportStatus.RUNNING && VideoExportRequestStore(context).load() != null) {
                start(context)
            }
        }

        fun clearNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
