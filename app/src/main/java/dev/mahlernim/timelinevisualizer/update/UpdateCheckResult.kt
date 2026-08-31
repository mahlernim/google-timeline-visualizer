package dev.mahlernim.timelinevisualizer.update

sealed interface UpdateCheckResult {
    data class Success(val update: AvailableAppUpdate?) : UpdateCheckResult
    data object Failure : UpdateCheckResult
}
