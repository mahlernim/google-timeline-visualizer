package dev.mahlernim.timelinevisualizer.update

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class DistributionUpdateManager(
    private val activity: AppCompatActivity,
    @Suppress("UNUSED_PARAMETER") updateLauncher: ActivityResultLauncher<IntentSenderRequest>,
    @Suppress("UNUSED_PARAMETER") onUpdateDownloaded: () -> Unit,
) {
    fun checkForUpdate(onResult: (UpdateCheckResult) -> Unit) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestUpdate() }
            onResult(result)
        }
    }

    fun startUpdate(update: AvailableAppUpdate): Boolean {
        val url = update.releaseUrl ?: return false
        return runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.isSuccess
    }

    fun onStart() = Unit

    fun onStop() = Unit

    fun completeUpdate() = Unit

    private fun fetchLatestUpdate(): UpdateCheckResult = runCatching {
        val connection = URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Update manifest request failed" }
            connection.inputStream.bufferedReader().use { reader ->
                val update = checkNotNull(GithubUpdateManifest.parse(reader.readText())) {
                    "Update manifest was invalid"
                }.toAvailableUpdate()
                UpdateCheckResult.Success(update)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { UpdateCheckResult.Failure }

    internal data class GithubUpdateManifest(
        val versionCode: Int,
        val versionName: String,
        val releaseUrl: String,
    ) {
        fun toAvailableUpdate(): AvailableAppUpdate = AvailableAppUpdate(versionCode, versionName, releaseUrl)

        companion object {
            fun parse(json: String): GithubUpdateManifest? = runCatching {
                val value = JsonParser.parseString(json).asJsonObject
                val versionCode = value.get("versionCode").asInt
                val versionName = value.get("versionName").asString.trim()
                val releaseUrl = value.get("releaseUrl").asString.trim()
                val uri = URI(releaseUrl)
                if (
                    versionCode <= 0 ||
                    versionName.isEmpty() ||
                    uri.scheme != "https" ||
                    uri.host != "github.com" ||
                    !uri.path.startsWith(RELEASE_PATH_PREFIX)
                ) {
                    return@runCatching null
                }
                GithubUpdateManifest(versionCode, versionName, releaseUrl)
            }.getOrNull()
        }
    }

    private companion object {
        const val UPDATE_MANIFEST_URL =
            "https://github.com/mahlernim/google-timeline-visualizer/releases/latest/download/update.json"
        const val RELEASE_PATH_PREFIX = "/mahlernim/google-timeline-visualizer/releases/tag/v"
        const val TIMEOUT_MILLIS = 5_000
    }
}
