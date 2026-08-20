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

    @Test
    fun ignoresInvalidAndDuplicateStoredAreas() {
        context.getSharedPreferences("safe_sharing_areas", Context.MODE_PRIVATE).edit()
            .putString(
                "areas_v1",
                """
                [
                  {"id":"home","name":"Home","latitude":37.5,"longitude":127.0,"radiusKm":3.0},
                  {"id":"invalid","name":"Invalid","latitude":91.0,"longitude":0.0,"radiusKm":3.0},
                  {"id":"home","name":"Duplicate","latitude":35.0,"longitude":129.0,"radiusKm":2.0}
                ]
                """.trimIndent(),
            )
            .commit()

        assertEquals(
            listOf(PrivacyArea("home", "Home", 37.5, 127.0, 3.0)),
            store.load(),
        )
    }
}
