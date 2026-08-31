package dev.mahlernim.timelinevisualizer.export

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoExportServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private lateinit var controller: ServiceController<VideoExportService>

    @Before
    fun setUp() {
        notificationManager.deleteNotificationChannel("video_creation")
        notificationManager.deleteNotificationChannel("video_completion")
        controller = Robolectric.buildService(VideoExportService::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        notificationManager.deleteNotificationChannel("video_creation")
        notificationManager.deleteNotificationChannel("video_completion")
    }

    @Test
    fun usesMediaProcessingTypeOnAndroid15AndNewer() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            VideoExportService.foregroundServiceTypeForApi(35),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            VideoExportService.foregroundServiceTypeForApi(36),
        )
    }

    @Test
    fun usesRecognizedDataSyncCompatibilityTypeOnOlderAndroid() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            VideoExportService.foregroundServiceTypeForApi(34),
        )
    }

    @Test
    fun separatesQuietProgressFromAlertingCompletionChannels() {
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            notificationManager.getNotificationChannel("video_creation").importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            notificationManager.getNotificationChannel("video_completion").importance,
        )
    }

    @Test
    fun duplicateStartStopsTheLatestDeliveredCommandAfterTerminalCleanup() {
        VideoExportRequestStore(context).clear()
        val service = controller.get()
        val shadowService = shadowOf(service)

        controller.startCommand(0, 41)
        assertEquals(Notification.VISIBILITY_PUBLIC, shadowService.lastForegroundNotification.visibility)
        controller.startCommand(0, 42)

        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadowService.stopSelfResultId == 42) return
            Thread.sleep(10)
        }
        fail("Service did not stop the latest delivered start ID")
    }

    @Test
    fun completedNotificationUsesExplicitAppActions() {
        val uri = Uri.parse("content://example/video/completed")
        val notification = controller.get().buildCompletedNotification(uri, "Completed video")

        assertEquals("video_completion", notification.channelId)
        assertEquals(Notification.CATEGORY_STATUS, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(2, notification.actions.size)

        val watch = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, watch.component?.className)
        assertEquals(MainActivity.ACTION_WATCH_VIDEO, watch.action)
        assertEquals(uri, watch.data)

        val share = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, share.component?.className)
        assertEquals(MainActivity.ACTION_SHARE_VIDEO, share.action)
        assertEquals(uri, share.data)
        assertTrue(share.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun retryableFailureUsesAlertingResultChannelAndRetryAction() {
        val detail = context.getString(dev.mahlernim.timelinevisualizer.R.string.map_tiles_unavailable)
        val notification = controller.get().buildFailedNotification(
            VideoExportFailure(VideoExportFailureKind.MAP_UNAVAILABLE, detail),
        )

        assertEquals("video_completion", notification.channelId)
        assertEquals(Notification.CATEGORY_ERROR, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(detail, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(1, notification.actions.size)

        val retry = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, retry.component?.className)
        assertEquals(MainActivity.ACTION_RETRY_VIDEO, retry.action)
        assertTrue(retry.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(retry.flags and android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun userActionFailureDoesNotOfferUnchangedRetry() {
        val notification = controller.get().buildFailedNotification(
            VideoExportFailure(VideoExportFailureKind.STORAGE_FULL, "Free storage"),
        )

        assertEquals(Notification.CATEGORY_ERROR, notification.category)
        assertTrue(notification.actions.isNullOrEmpty())
    }
}
