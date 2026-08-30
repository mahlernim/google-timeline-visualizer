package dev.mahlernim.timelinevisualizer.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class DistributionUpdateManager(
    private val activity: AppCompatActivity,
    private val updateLauncher: ActivityResultLauncher<IntentSenderRequest>,
    private val onUpdateDownloaded: () -> Unit,
) {
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
    private var availableUpdateInfo: AppUpdateInfo? = null
    private var listening = false
    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) onUpdateDownloaded()
    }

    fun checkForUpdate(onResult: (AvailableAppUpdate?) -> Unit) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener(activity) { info ->
                val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                availableUpdateInfo = info.takeIf { available }
                onResult(
                    info.takeIf { available }?.let {
                        AvailableAppUpdate(versionCode = it.availableVersionCode())
                    },
                )
            }
            .addOnFailureListener(activity) { onResult(null) }
    }

    fun startUpdate(@Suppress("UNUSED_PARAMETER") update: AvailableAppUpdate): Boolean {
        val info = availableUpdateInfo ?: return false
        return runCatching {
            appUpdateManager.startUpdateFlowForResult(
                info,
                updateLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
            )
        }.getOrDefault(false)
    }

    fun onStart() {
        if (!listening) {
            appUpdateManager.registerListener(installListener)
            listening = true
        }
        appUpdateManager.appUpdateInfo.addOnSuccessListener(activity) { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) onUpdateDownloaded()
        }
    }

    fun onStop() {
        if (listening) {
            appUpdateManager.unregisterListener(installListener)
            listening = false
        }
    }

    fun completeUpdate() {
        appUpdateManager.completeUpdate()
    }
}
