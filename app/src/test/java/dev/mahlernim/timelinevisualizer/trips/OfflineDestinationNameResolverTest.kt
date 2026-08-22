package dev.mahlernim.timelinevisualizer.trips

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDestinationNameResolverTest {
    private val resolver = OfflineDestinationNameResolver(
        ApplicationProvider.getApplicationContext<Context>(),
    )

    @Test
    fun resolvesNearestCityAndCountryWithoutNetwork() {
        assertEquals("Tokyo, Japan", resolver.resolve(35.6812, 139.7671))
    }

    @Test
    fun returnsNoGuessForRemoteOcean() {
        assertNull(resolver.resolve(0.0, -140.0))
    }
}
