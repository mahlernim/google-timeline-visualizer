package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

class TimelineSourceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): Uri? = preferences.getString(KEY_URI, null)?.let(Uri::parse)

    fun replace(uri: Uri): Boolean = preferences.edit()
        .putString(KEY_URI, uri.toString())
        .commit()

    fun clear(): Uri? {
        val previous = load()
        preferences.edit { remove(KEY_URI) }
        return previous
    }

    internal fun clearForTest() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_NAME = "timeline_source"
        private const val KEY_URI = "document_uri_v1"
    }
}
