package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalOnboardingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    @After
    fun clearState() {
        context.getSharedPreferences(JournalOnboardingStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun completionPersistsAcrossStoreInstances() {
        assertFalse(JournalOnboardingStore(context).isCompleted())

        JournalOnboardingStore(context).complete()

        assertTrue(JournalOnboardingStore(context).isCompleted())
    }

    @Test
    fun olderCompletionVersionDoesNotSuppressNewIntroduction() {
        context.getSharedPreferences(JournalOnboardingStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putInt("completed_version", JournalOnboardingStore.CURRENT_VERSION - 1).commit()

        assertFalse(JournalOnboardingStore(context).isCompleted())
    }
}
