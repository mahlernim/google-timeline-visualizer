package dev.mahlernim.timelinevisualizer.model

object VideoDuration {
    const val MIN_SECONDS = 10
    const val MAX_SECONDS = 300
    const val DEFAULT_SECONDS = 30
    const val LONG_DURATION_SECONDS = 60

    val presets: List<Int> = listOf(10, 15, 20, 30, 45, 60)

    fun parseCustom(raw: CharSequence?): Int? {
        val text = raw?.toString()?.trim().orEmpty()
        if (!text.matches(Regex("[0-9]+"))) return null
        return text.toIntOrNull()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }
    }
}
