package dev.mahlernim.timelinevisualizer.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivacyAreaStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PrivacyAreaStore(context).also { it.save(emptyList()) }

    @After
    fun tearDown() = store.save(emptyList())

    @Test
    fun storesSeveralNamedAreasWithIndependentRadii() {
        val areas = listOf(
            PrivacyArea("home", "Home", 37.5665, 126.9780, 3.0),
            PrivacyArea("work", "Work", 35.1796, 129.0756, 1.5),
        )

        store.save(areas)

        assertEquals(areas, PrivacyAreaStore(context).load())
    }
}
