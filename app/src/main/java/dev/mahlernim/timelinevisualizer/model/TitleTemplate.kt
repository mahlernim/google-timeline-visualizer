package dev.mahlernim.timelinevisualizer.model

object TitleTemplate {
    fun resolve(template: String, year: Int, name: String, fallback: String): String =
        resolve(template, year.toString(), name, fallback)

    fun resolve(template: String, yearLabel: String, name: String, fallback: String): String {
        val resolved = template
            .replace("{year}", yearLabel, ignoreCase = true)
            .replace("{name}", name.trim(), ignoreCase = true)
            .trim()
        return resolved.ifBlank { fallback }
    }
}
