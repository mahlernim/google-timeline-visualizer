package dev.mahlernim.timelinevisualizer.presets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.render.CameraSettings
import dev.mahlernim.timelinevisualizer.render.VideoAspectRatio
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = PresetRepository(context)
    private val values = PresetValues.from(CameraSettings.DEFAULT)

    @Before
    @After
    fun clear() {
        context.getSharedPreferences("video-presets", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun saveRenameDefaultAndDeleteAreExplicitAndDurable() {
        val saved = repository.add("  City   trips  ", values)
        assertEquals("City trips", saved.name)
        assertNull(repository.defaultPresetId())

        repository.setDefaultPresetId(saved.id)
        assertEquals(saved.id, PresetRepository(context).defaultPresetId())

        assertEquals("Quiet trips", repository.rename(saved.id, "Quiet trips")?.name)
        assertTrue(repository.delete(saved.id))
        assertTrue(repository.presets().filterNot(VideoPreset::builtIn).isEmpty())
        assertNull(repository.defaultPresetId())
    }

    @Test
    fun duplicateNamesUseCaseInsensitiveComparisonWithoutReplacement() {
        repository.add("Travel", values)

        assertEquals(PresetNameResult.Duplicate, repository.validateName(" travel "))
        assertEquals(1, repository.presets().count { !it.builtIn })
    }

    @Test
    fun malformedAndOversizedStorageFailClosed() {
        val raw = context.getSharedPreferences("video-presets", Context.MODE_PRIVATE)
        raw.edit().putString("presets-v1", "not json").commit()
        assertTrue(repository.presets().filterNot(VideoPreset::builtIn).isEmpty())

        raw.edit().putString("presets-v1", "x".repeat(70_000)).commit()
        assertTrue(repository.presets().filterNot(VideoPreset::builtIn).isEmpty())
    }

    @Test
    fun builtInsAreImmutableAndExactMatchesAreReused() {
        val trip = repository.presets().first { it.id == PresetRepository.TRIP_CLOSE_UP_ID }

        assertTrue(trip.builtIn)
        assertEquals("Trip defaults", trip.name)
        assertEquals(VideoAspectRatio.PORTRAIT, trip.values.aspectRatio)
        assertNull(repository.rename(trip.id, "Renamed"))
        assertNull(repository.replace(trip.id, values))
        assertTrue(!repository.delete(trip.id))
        assertEquals(trip, repository.exactMatch(trip.values))
    }

    @Test
    fun replacingUserPresetPreservesIdentityAndName() {
        val saved = repository.add("My trip", values)
        val changed = values.copy(aspectRatio = VideoAspectRatio.PORTRAIT)

        val replaced = repository.replace(saved.id, changed)

        assertEquals(saved.id, replaced?.id)
        assertEquals(saved.name, replaced?.name)
        assertEquals(changed, PresetRepository(context).presets().first { it.id == saved.id }.values)
    }

    @Test
    fun namesAreBoundedByUnicodeCodePoints() {
        assertEquals(PresetNameResult.Empty, repository.validateName("   "))
        assertEquals(PresetNameResult.TooLong, repository.validateName("😀".repeat(41)))
        assertEquals(PresetNameResult.Valid("😀".repeat(40)), repository.validateName("😀".repeat(40)))
    }
}
