package dev.mahlernim.timelinevisualizer.trips

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

class TripsStore(context: Context) {
    private val preferences = context.getSharedPreferences("trips_lab", Context.MODE_PRIVATE)

    fun list(): List<TripProject> = decode(preferences.getString(KEY_PROJECTS, null))
        .sortedByDescending(TripProject::startDate)

    fun upsert(project: TripProject) {
        save(listOf(project) + list().filterNot { it.id == project.id })
    }

    fun create(
        title: String,
        start: LocalDate,
        end: LocalDate,
        kind: TripKind,
        titleMode: ProjectTitleMode = ProjectTitleMode.CUSTOM,
    ): TripProject = TripProject(
        id = UUID.randomUUID().toString(),
        title = title.trim().ifBlank { defaultTitle(kind, start) },
        startDate = start,
        endDate = end,
        kind = kind,
        createdAtMillis = System.currentTimeMillis(),
        titleMode = titleMode,
    ).also(::upsert)

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun removeAll(ids: Set<String>) {
        save(list().filterNot { it.id in ids })
    }

    fun dismissedSuggestionIds(): Set<String> = preferences.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    fun dismissSuggestion(id: String) {
        preferences.edit { putStringSet(KEY_DISMISSED, dismissedSuggestionIds() + id) }
    }

    private fun save(projects: List<TripProject>) {
        val array = JSONArray()
        projects.forEach { project ->
            array.put(JSONObject().apply {
                put("id", project.id)
                put("title", project.title)
                put("startDate", project.startDate.toString())
                put("endDate", project.endDate.toString())
                put("kind", project.kind.name)
                put("createdAtMillis", project.createdAtMillis)
                put("titleMode", project.titleMode.name)
            })
        }
        preferences.edit { putString(KEY_PROJECTS, array.toString()) }
    }

    private fun decode(raw: String?): List<TripProject> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val start = runCatching { LocalDate.parse(item.optString("startDate")) }.getOrNull() ?: continue
                    val end = runCatching { LocalDate.parse(item.optString("endDate")) }.getOrNull() ?: continue
                    val kind = runCatching { TripKind.valueOf(item.optString("kind")) }.getOrDefault(TripKind.TRIP)
                    add(TripProject(
                        id = id,
                        title = item.optString("title").ifBlank { defaultTitle(kind, start) },
                        startDate = start,
                        endDate = end,
                        kind = kind,
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                        titleMode = runCatching {
                            ProjectTitleMode.valueOf(item.optString("titleMode"))
                        }.getOrDefault(ProjectTitleMode.CUSTOM),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun defaultTitle(kind: TripKind, start: LocalDate): String = when (kind) {
        TripKind.TRIP -> "Trip"
        TripKind.MONTHLY_RECAP -> "${start.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${start.year} recap"
        TripKind.YEARLY_RECAP -> "${start.year} recap"
        TripKind.CUSTOM_RECAP -> "Recap"
        TripKind.RAW_DATA -> "Raw data"
    }

    companion object {
        private const val KEY_PROJECTS = "projects_v1"
        private const val KEY_DISMISSED = "dismissed_suggestions_v1"
    }
}
