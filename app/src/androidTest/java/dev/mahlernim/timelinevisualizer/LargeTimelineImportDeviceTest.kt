package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.data.LocationFilterMode
import dev.mahlernim.timelinevisualizer.ui.TimelineView
import dev.mahlernim.timelinevisualizer.ui.LocationFilterPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeTimelineImportDeviceTest {
    @Test
    fun importsDenseLongGapTimelineBelowSixteenMegabytesInPortuguese() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        setAppLocales("pt-BR")
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
        LocationFilterPreferences(context).save(LocationFilterMode.CONSERVATIVE)
        TimelineSourceStore(context).clear()
        val source = File(context.cacheDir, "dense-long-gap-timeline.json")
        writeDenseLongGapTimeline(source, 14L * 1024 * 1024)

        try {
            assertImportCompletes(context, source)
            assertTrue(source.length() >= 14L * 1024 * 1024)
            assertTrue(source.length() < 16L * 1024 * 1024)
        } finally {
            setAppLocales("")
            LocationFilterPreferences(context).reset()
            TimelineSourceStore(context).clear()
            source.delete()
        }
    }

    private fun setAppLocales(languageTags: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTags))
        }
    }

    @Test
    fun importsFortyFiveMegabyteTimelineWithoutTerminatingTheApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
        TimelineSourceStore(context).clear()
        val source = File(context.cacheDir, "large-timeline.json")
        writeLargeTimeline(source, 45L * 1024 * 1024)

        try {
            assertImportCompletes(context, source)
            assertTrue(source.length() >= 45L * 1024 * 1024)
        } finally {
            TimelineSourceStore(context).clear()
            source.delete()
        }
    }

    private fun assertImportCompletes(context: Context, source: File) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        var imported = false
        var previewOpened = false
        val sourceStore = TimelineSourceStore(context)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.importTimeline(Uri.fromFile(source))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.loadingGroup).visibility)
                assertEquals(false, activity.findViewById<View>(R.id.importButton).isEnabled)
            }
            val deadline = System.currentTimeMillis() + 300_000L
            while (System.currentTimeMillis() < deadline && !imported) {
                scenario.onActivity { activity ->
                    val loading = activity.findViewById<View>(R.id.loadingGroup).visibility == View.VISIBLE
                    if (loading) assertEquals(false, activity.findViewById<View>(R.id.importButton).isEnabled)
                    if (!loading && sourceStore.importInProgress() == null && !previewOpened) {
                        activity.findViewById<View>(R.id.navigationCreate).performClick()
                        activity.findViewById<View>(R.id.tripVideoChoice).performClick()
                        activity.findViewById<View>(R.id.createTripButton).performClick()
                        activity.findViewById<View>(R.id.wizardContinueButton).performClick()
                        activity.findViewById<View>(R.id.wizardContinueButton).performClick()
                        previewOpened = true
                    }
                    imported = previewOpened &&
                        activity.findViewById<TimelineView>(R.id.timelineView).isCameraReady &&
                        activity.findViewById<View>(R.id.playButton).isEnabled
                }
                if (!imported) Thread.sleep(100)
            }
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.editorGroup).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.loadingGroup).visibility)
                assertEquals(true, activity.findViewById<View>(R.id.importButton).isEnabled)
                assertEquals(true, activity.findViewById<View>(R.id.playButton).isEnabled)
            }
        }
        assertTrue(imported)
        assertEquals(null, sourceStore.importInProgress())
    }

    private fun writeDenseLongGapTimeline(file: File, minimumBytes: Long) {
        file.bufferedWriter().use { writer ->
            writer.write("{\"semanticSegments\":[")
            var firstSegment = true
            var pointIndex = 0
            while (true) {
                if (!firstSegment) writer.write(','.code)
                firstSegment = false
                writer.write("{\"startTime\":\"2020-01-01T00:00:00Z\",\"timelinePath\":[")
                repeat(1_000) { offset ->
                    if (offset > 0) writer.write(','.code)
                    val region = (pointIndex / 1_000) % 2
                    val latitude = 35.0 + region * 10.0 + (pointIndex % 1_000) / 1_000_000.0
                    val longitude = 10.0 + region * 120.0 + (pointIndex % 1_000) / 1_000_000.0
                    writer.write(
                        "{\"point\":\"$latitude,$longitude\"," +
                            "\"durationMinutesOffsetFromStartTime\":$pointIndex}",
                    )
                    pointIndex += 1
                }
                writer.write("]}")
                writer.flush()
                if (file.length() >= minimumBytes) break
            }
            writer.write("]}")
        }
    }

    private fun writeLargeTimeline(file: File, minimumBytes: Long) {
        file.bufferedWriter().use { writer ->
            writer.write("{\"semanticSegments\":[")
            var firstSegment = true
            var pointIndex = 0
            var segmentCount = 0
            while (true) {
                if (!firstSegment) writer.write(','.code)
                firstSegment = false
                writer.write("{\"startTime\":\"2020-01-01T00:00:00Z\",\"timelinePath\":[")
                repeat(1_000) { offset ->
                    if (offset > 0) writer.write(','.code)
                    val latitude = 35.0 + (pointIndex % 100_000) / 1_000_000.0
                    val longitude = 126.0 + (pointIndex % 100_000) / 1_000_000.0
                    writer.write(
                        "{\"point\":\"$latitude,$longitude\"," +
                            "\"durationMinutesOffsetFromStartTime\":$pointIndex}",
                    )
                    pointIndex += 1
                }
                writer.write("]}")
                segmentCount += 1
                if (segmentCount % 100 == 0) {
                    writer.flush()
                    if (file.length() >= minimumBytes) break
                }
            }
            writer.write("]}")
        }
    }
}
