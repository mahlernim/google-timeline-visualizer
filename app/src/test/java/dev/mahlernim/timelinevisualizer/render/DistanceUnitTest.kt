package dev.mahlernim.timelinevisualizer.render

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DistanceUnitTest {
    @Test
    fun automaticUsesMilesForUnitedStatesAndUnitedKingdom() {
        assertEquals(DistanceUnit.MILES, DistanceUnit.automatic(Locale.US))
        assertEquals(DistanceUnit.MILES, DistanceUnit.automatic(Locale.UK))
    }

    @Test
    fun automaticUsesKilometersForMetricRegions() {
        assertEquals(DistanceUnit.KILOMETERS, DistanceUnit.automatic(Locale.KOREA))
        assertEquals(DistanceUnit.KILOMETERS, DistanceUnit.automatic(Locale.JAPAN))
    }

    @Test
    @Config(sdk = [27])
    fun androidEightFallbackUsesTheSameCommonRegionConventions() {
        assertEquals(DistanceUnit.MILES, DistanceUnit.automatic(Locale.US))
        assertEquals(DistanceUnit.MILES, DistanceUnit.automatic(Locale.UK))
        assertEquals(DistanceUnit.KILOMETERS, DistanceUnit.automatic(Locale.GERMANY))
    }

    @Test
    fun milesConvertFromKilometersOnlyForDisplay() {
        assertEquals(62.1371192237334, DistanceUnit.MILES.fromKilometers(100.0), 1e-12)
        assertEquals(100.0, DistanceUnit.KILOMETERS.fromKilometers(100.0), 0.0)
    }

    @Test
    fun visibleUnitSelectionsResolveStoredAutomaticWithoutOfferingIt() {
        assertEquals(listOf(DistanceUnitPreference.KILOMETERS, DistanceUnitPreference.MILES), DistanceUnitPreference.selectable)
        assertEquals(0, DistanceUnitPreference.selectionIndex(DistanceUnitPreference.AUTOMATIC, Locale.KOREA))
        assertEquals(1, DistanceUnitPreference.selectionIndex(DistanceUnitPreference.AUTOMATIC, Locale.US))
        assertEquals(DistanceUnitPreference.KILOMETERS, DistanceUnitPreference.fromSelection(0))
        assertEquals(DistanceUnitPreference.MILES, DistanceUnitPreference.fromSelection(1))
    }
}
