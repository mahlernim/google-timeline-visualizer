package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineSourceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = TimelineSourceStore(context).also(TimelineSourceStore::clearForTest)

    @After
    fun tearDown() = store.clearForTest()

    @Test
    fun replacingSourceKeepsOnlyTheLatestDocument() {
        val first = Uri.parse("content://example/first.json")
        val second = Uri.parse("content://example/second.json")

        assertTrue(store.replace(first))
        assertTrue(store.replace(second))
        assertEquals(second, store.load())
        assertEquals(second, store.clear())
        assertNull(store.load())
    }
}
