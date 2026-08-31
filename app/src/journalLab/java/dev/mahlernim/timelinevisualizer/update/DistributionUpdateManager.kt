package dev.mahlernim.timelinevisualizer.update

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity

class DistributionUpdateManager(
    @Suppress("UNUSED_PARAMETER") activity: AppCompatActivity,
    @Suppress("UNUSED_PARAMETER") updateLauncher: ActivityResultLauncher<IntentSenderRequest>,
    @Suppress("UNUSED_PARAMETER") onUpdateDownloaded: () -> Unit,
) {
    fun checkForUpdate(onResult: (UpdateCheckResult) -> Unit) = onResult(UpdateCheckResult.Success(null))

    fun startUpdate(@Suppress("UNUSED_PARAMETER") update: AvailableAppUpdate): Boolean = false

    fun onStart() = Unit

    fun onStop() = Unit

    fun completeUpdate() = Unit
}
