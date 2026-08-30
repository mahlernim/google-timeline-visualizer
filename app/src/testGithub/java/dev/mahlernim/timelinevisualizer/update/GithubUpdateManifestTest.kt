package dev.mahlernim.timelinevisualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubUpdateManifestTest {
    @Test
    fun parsesPublishedReleaseMetadata() {
        val update = DistributionUpdateManager.GithubUpdateManifest.parse(
            """
            {
              "versionCode": 51,
              "versionName": "3.0.9",
              "releaseUrl": "https://github.com/mahlernim/google-timeline-visualizer/releases/tag/v3.0.9"
            }
            """.trimIndent(),
        )?.toAvailableUpdate()

        assertEquals(51, update?.versionCode)
        assertEquals("3.0.9", update?.versionName)
        assertEquals(
            "https://github.com/mahlernim/google-timeline-visualizer/releases/tag/v3.0.9",
            update?.releaseUrl,
        )
    }

    @Test
    fun rejectsNonProjectReleaseUrls() {
        val update = DistributionUpdateManager.GithubUpdateManifest.parse(
            """
            {
              "versionCode": 51,
              "versionName": "3.0.9",
              "releaseUrl": "https://example.com/releases/tag/v3.0.9"
            }
            """.trimIndent(),
        )

        assertNull(update)
    }

    @Test
    fun rejectsInvalidVersionCodes() {
        val update = DistributionUpdateManager.GithubUpdateManifest.parse(
            """
            {
              "versionCode": 0,
              "versionName": "3.0.9",
              "releaseUrl": "https://github.com/mahlernim/google-timeline-visualizer/releases/tag/v3.0.9"
            }
            """.trimIndent(),
        )

        assertNull(update)
    }
}
