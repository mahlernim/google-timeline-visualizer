package dev.mahlernim.timelinevisualizer.update

object UpdatePromptPolicy {
    const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    const val DISMISSAL_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    fun shouldCheck(nowMillis: Long, lastCheckMillis: Long): Boolean =
        lastCheckMillis <= 0L ||
            nowMillis < lastCheckMillis ||
            nowMillis - lastCheckMillis >= CHECK_INTERVAL_MILLIS

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
