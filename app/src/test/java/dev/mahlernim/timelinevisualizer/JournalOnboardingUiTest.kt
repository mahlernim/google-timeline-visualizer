package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalOnboardingUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun resetState() {
        context.deleteDatabase("travel-journal.db")
        context.getSharedPreferences(JournalOnboardingStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun closeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    @Test
    fun firstRunShowsBrandedPageOneWithoutBottomNavigation() {
        val activity = launchActivity()
        waitForOnboarding(activity)

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalOnboardingScreen).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomNavigation).visibility)
        assertEquals("Turn your journeys into a Travel Journal", activity.findViewById<TextView>(R.id.onboardingPageTitle).text)
        assertEquals(1f, activity.findViewById<View>(R.id.onboardingDotOne).alpha)
        assertEquals(0.24f, activity.findViewById<View>(R.id.onboardingDotTwo).alpha)
        assertEquals(0.24f, activity.findViewById<View>(R.id.onboardingDotThree).alpha)
        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.onboardingBackButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.onboardingNextButton).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.onboardingLanguageButton).visibility)
        assertEquals(
            activity.getString(R.string.language_system_default),
            activity.findViewById<TextView>(R.id.onboardingLanguageButton).text,
        )
        assertTrue(activity.findViewById<TextView>(R.id.onboardingPageTitle).isAccessibilityHeading)
    }

    @Test
    fun recreationKeepsTheCurrentPage() {
        val activity = launchActivity()
        waitForOnboarding(activity)
        activity.findViewById<View>(R.id.onboardingNextButton).performClick()
        waitUntil { activity.findViewById<TextView>(R.id.onboardingPageTitle).text == "Your journeys, your device" }

        controller = requireNotNull(controller).recreate()
        val recreated = requireNotNull(controller).get()
        waitUntil { recreated.findViewById<TextView>(R.id.onboardingPageTitle).text == "Your journeys, your device" }

        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.journalOnboardingScreen).visibility)
        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.onboardingBackButton).visibility)
        assertEquals(View.GONE, recreated.findViewById<View>(R.id.onboardingLanguageButton).visibility)
    }

    @Test
    fun notNowPersistsAndLeavesASetupCardInTheLibrary() {
        var activity = launchActivity()
        waitForOnboarding(activity)
        activity.findViewById<View>(R.id.onboardingSkipButton).performClick()
        waitUntil { activity.findViewById<View>(R.id.onboardingFinalActions).visibility == View.VISIBLE }
        activity.findViewById<View>(R.id.onboardingNotNowButton).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalSetupCard).visibility)
        requireNotNull(controller).pause().stop().destroy()
        controller = null

        activity = launchActivity()
        waitUntil { activity.findViewById<View>(R.id.videosScreen).visibility == View.VISIBLE }
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalOnboardingScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalSetupCard).visibility)
    }

    @Test
    fun finalPageShowsActionsAndExpandsTheLocalFileExplanation() {
        val activity = launchActivity()
        waitForOnboarding(activity)
        activity.findViewById<View>(R.id.onboardingSkipButton).performClick()
        waitUntil { activity.findViewById<View>(R.id.onboardingFinalActions).visibility == View.VISIBLE }

        val detail = activity.findViewById<TextView>(R.id.onboardingFileDisclosureDetail)
        assertEquals("Start your Travel Journal", activity.findViewById<TextView>(R.id.onboardingPageTitle).text)
        assertEquals(View.GONE, detail.visibility)
        activity.findViewById<View>(R.id.onboardingFileDisclosureButton).performClick()

        assertEquals(View.VISIBLE, detail.visibility)
        assertTrue(detail.text.contains("read locally"))
    }

    @Test
    fun settingsReplayReturnsToSettingsWithoutChangingCompletion() {
        JournalOnboardingStore(context).complete()
        val activity = launchActivity()
        waitUntil { activity.findViewById<View>(R.id.videosScreen).visibility == View.VISIBLE }
        activity.findViewById<View>(R.id.navigationSettings).performClick()
        activity.findViewById<View>(R.id.settingsJournalHowItWorksButton).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.journalOnboardingScreen).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.onboardingBackButton).visibility)
        activity.findViewById<View>(R.id.onboardingBackButton).performClick()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
        assertTrue(JournalOnboardingStore(context).isCompleted())
    }

    @Test
    fun existingJournalSkipsTheIntroduction() {
        val database = JournalDatabase.open(context)
        runBlocking {
            JournalRepository(database).createJournal(
                JournalEntity(
                    id = "existing",
                    name = "Travel Journal",
                    isPrimary = true,
                    createdAtEpochMillis = 1_000L,
                ),
            )
        }
        database.close()

        val activity = launchActivity()
        waitUntil { activity.findViewById<View>(R.id.videosScreen).visibility == View.VISIBLE }

        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalOnboardingScreen).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalSetupCard).visibility)

        activity.findViewById<View>(R.id.navigationSettings).performClick()
        waitUntil { activity.findViewById<View>(R.id.settingsWhyImportButton).visibility == View.VISIBLE }
        val detail = activity.findViewById<TextView>(R.id.settingsWhyImportDetail)
        assertEquals(View.GONE, detail.visibility)
        activity.findViewById<View>(R.id.settingsWhyImportButton).performClick()
        assertEquals(View.VISIBLE, detail.visibility)
    }

    @Test
    fun incomingJsonBypassesOnboardingAndIsConsumedBeforeRecreation() {
        context.getSharedPreferences("display", Context.MODE_PRIVATE)
            .edit().putBoolean("map_privacy_accepted_v1", true).commit()
        val source = rawTimeline()
        val launchIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(source), "application/json")
        }
        controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent).setup()
        val activity = requireNotNull(controller).get()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.journalOnboardingScreen).visibility)
        waitUntil { activity.journalMetadataReady() }
        assertNull(activity.intent.data)
        assertFalse(activity.journalRouteReady())

        controller = requireNotNull(controller).recreate()
        val recreated = requireNotNull(controller).get()
        waitUntil { recreated.journalMetadataReady() }
        assertFalse(recreated.journalRouteReady())
        recreated.findViewById<View>(R.id.navigationCreate).performClick()
        recreated.findViewById<View>(R.id.customRecapChoice).performClick()
        waitUntil { recreated.currentJourneyPoints().size >= 2 }
        assertEquals(View.GONE, recreated.findViewById<View>(R.id.journalOnboardingScreen).visibility)
    }

    private fun launchActivity(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return requireNotNull(controller).get()
    }

    private fun waitForOnboarding(activity: MainActivity) {
        waitUntil { activity.findViewById<View>(R.id.journalOnboardingScreen).visibility == View.VISIBLE }
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        error("The asynchronous Journal operation did not finish")
    }

    private fun rawTimeline(): File = File.createTempFile("onboarding-direct", ".json", context.cacheDir).apply {
        writeText(
            """
            {"rawSignals":[
              {"position":{"LatLng":"geo:37.50,127.00","timestamp":"2026-08-01T00:00:00Z","accuracyMeters":10}},
              {"position":{"LatLng":"geo:37.51,127.01","timestamp":"2026-08-01T00:10:00Z","accuracyMeters":10}}
            ]}
            """.trimIndent(),
        )
    }
}
