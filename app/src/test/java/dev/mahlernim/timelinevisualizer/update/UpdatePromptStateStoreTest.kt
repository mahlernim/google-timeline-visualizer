package dev.mahlernim.timelinevisualizer.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatePromptStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("app_update_prompt", Context.MODE_PRIVATE)
    private val store = UpdatePromptStateStore(context)

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun failedAttemptDoesNotAdvanceTheSuccessfulCheckTime() {
        store.recordSuccess(1_000L)
        store.recordAttempt(2_000L)

        assertEquals(1_000L, store.lastSuccessfulCheckMillis)
        assertEquals(2_000L, store.lastAttemptMillis)
    }
}
