package dev.mahlernim.timelinevisualizer.update

data class AvailableAppUpdate(
    val versionCode: Int,
    val versionName: String? = null,
    val releaseUrl: String? = null,
)
