package dev.mahlernim.timelinevisualizer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LocalizedLanguageResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    @Config(sdk = [35], qualifiers = "id-rID")
    fun indonesianResourcesLoadAndFormatVersion() = assertLocalizedVersion("id")

    @Test
    @Config(sdk = [35], qualifiers = "vi-rVN")
    fun vietnameseResourcesLoadAndFormatVersion() = assertLocalizedVersion("vi")

    private fun assertLocalizedVersion(expectedLanguage: String) {
        assertEquals(expectedLanguage, context.resources.configuration.locales[0].language)
        val localizedVersion = context.getString(R.string.app_version, "3.0.0", 42)
        assertFalse(localizedVersion.contains("%1\$s"))
        assertFalse(localizedVersion.contains("%2\$d"))
        assertNotEquals("Version 3.0.0 (42)", localizedVersion)
        assertTrue(localizedVersion.contains("3.0.0"))
        assertTrue(localizedVersion.contains("42"))
    }
}
