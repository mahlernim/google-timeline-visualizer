package dev.mahlernim.timelinevisualizer.update

object UpdatePromptPolicy {
    const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    const val FAILURE_RETRY_INTERVAL_MILLIS = 60L * 60L * 1_000L
    const val DISMISSAL_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    fun shouldCheck(
        nowMillis: Long,
        lastSuccessfulCheckMillis: Long,
        lastAttemptMillis: Long,
    ): Boolean {
        if (
            lastSuccessfulCheckMillis > 0L &&
            nowMillis >= lastSuccessfulCheckMillis &&
            nowMillis - lastSuccessfulCheckMillis < CHECK_INTERVAL_MILLIS
        ) return false
        return lastAttemptMillis <= 0L ||
            nowMillis < lastAttemptMillis ||
            nowMillis - lastAttemptMillis >= FAILURE_RETRY_INTERVAL_MILLIS
    }

    fun shouldPrompt(
        update: AvailableAppUpdate,
        installedVersionCode: Int,
        dismissedVersionCode: Int,
        dismissedAtMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (update.versionCode <= installedVersionCode) return false
        if (dismissedVersionCode != update.versionCode) return true
        if (dismissedAtMillis <= 0L || nowMillis < dismissedAtMillis) return true
        return nowMillis - dismissedAtMillis >= DISMISSAL_INTERVAL_MILLIS
    }
}
