package dev.mahlernim.timelinevisualizer.render

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.journal.importer.TimelineJournalImportAdapter
import dev.mahlernim.timelinevisualizer.journal.route.JournalRouteService
import dev.mahlernim.timelinevisualizer.journal.route.RouteDetail
import dev.mahlernim.timelinevisualizer.journal.route.RouteSource
import dev.mahlernim.timelinevisualizer.journal.route.journeyForRange
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in local evaluation. No private source path or values are committed to test fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecapPrivateTimelineTest {
    @Test fun evaluatesRealJournalRouteAndWritesOnlyAggregateEvidence() = runBlocking {
        val sourcePath = System.getenv("TIMELINE_RECAP_INPUT")
        val outputPath = System.getenv("TIMELINE_RECAP_OUTPUT")
        assumeTrue("Private evaluation requires explicit input and output paths", sourcePath != null && outputPath != null)
        val source = File(requireNotNull(sourcePath))
        assumeTrue("Private evaluation input is not available", source.isFile)
        val zone = ZoneId.of(System.getenv("TIMELINE_RECAP_ZONE") ?: ZoneId.systemDefault().id)
        val routeDetail = RouteDetail.valueOf(System.getenv("TIMELINE_RECAP_ROUTE_DETAIL") ?: "DETAILED")
        val first = YearMonth.parse(System.getenv("TIMELINE_RECAP_FIRST_MONTH") ?: "2025-01")
        val last = YearMonth.parse(System.getenv("TIMELINE_RECAP_LAST_MONTH") ?: "2026-07")
        val import = source.inputStream().buffered().use { input ->
            TimelineJournalImportAdapter().adapt(input, null, source.lastModified(), JournalMatchClassification.NEW_JOURNAL)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = JournalRepository(database)
            repository.createJournalAndImport(
                JournalEntity("local-recap-evaluation", "Local evaluation", true, 0L), import,
            )
            val service = JournalRouteService(repository)
            val personalities = sortedMapOf<String, Int>()
            val modes = sortedMapOf<String, Int>()
            val sensitivityPersonalities = sortedMapOf<String, Int>()
            val anomalySourceCounts = sortedMapOf<String, Int>()
            var zeroDurationSegments = 0
            var zeroDurationKm = 0.0
            var negativeDurationSegments = 0
            var positiveDurationExtremeSegments = 0
            var positiveDurationExtremeKm = 0.0
            var sensitivityChangedMonths = 0
            var chaosMonthsWithPeakShareAtLeast35Percent = 0
            var count = 0
            var empty = 0
            var knownKm = 0.0
            var longSpanKm = 0.0
            var extremeSpeedKm = 0.0
            var extremeSpeedSegments = 0
            var analyzedPoints = 0
            var month = first
            while (month <= last) {
                val route = service.route("local-recap-evaluation", month.atDay(1).atStartOfDay(zone).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant(), routeDetail = routeDetail)
                val journey = route.journeyForRange(TimelinePeriod(month, month), zone)
                val recap = RecapAnalyzer.analyze(journey, zone)
                assertTrue(recap.totalDistanceKm.isFinite() && recap.totalDistanceKm >= 0.0)
                assertEquals(journey.knownDistanceKm, recap.totalDistanceKm, 1e-6)
                assertTrue(recap.movementDays in 0..month.lengthOfMonth())
                recap.dominantModeShare?.let { assertTrue(it in 0.50..1.0) }
                personalities[recap.personalityId.name] = personalities.getOrDefault(recap.personalityId.name, 0) + 1
                modes[recap.dominantMode.name] = modes.getOrDefault(recap.dominantMode.name, 0) + 1
                count++
                if (recap.totalDistanceKm == 0.0) empty++
                knownKm += recap.totalDistanceKm
                analyzedPoints += journey.points.size
                if (recap.personalityId == RecapPersonalityId.CHAOS_COORDINATOR && recap.personalityValue >= 0.35) {
                    chaosMonthsWithPeakShareAtLeast35Percent++
                }
                val semanticPoints = route.spans.filter { it.source == RouteSource.SEMANTIC_PATH }.flatMap { it.points }.toSet()
                val detailedPoints = route.spans.filter { it.source == RouteSource.DETAILED }.flatMap { it.points }.toSet()
                val diagnosticExcludedIndices = mutableListOf<Int>()
                for (index in 1..journey.points.lastIndex) {
                    if (!journey.isConnectedToPrevious(index) || journey.isInferredTransferFromPrevious(index)) continue
                    val km = journey.cumulativeDistanceKm[index] - journey.cumulativeDistanceKm[index - 1]
                    val hours = Duration.between(journey.points[index - 1].instant, journey.points[index].instant).toMillis() / 3_600_000.0
                    if (hours > 6.0) longSpanKm += km
                    if (hours <= 0.0 && km > 0.0 || hours > 0.0 && km / hours > 2000.0) {
                        extremeSpeedKm += km
                        extremeSpeedSegments++
                        diagnosticExcludedIndices += index
                        when {
                            hours == 0.0 -> { zeroDurationSegments++; zeroDurationKm += km }
                            hours < 0.0 -> negativeDurationSegments++
                            else -> { positiveDurationExtremeSegments++; positiveDurationExtremeKm += km }
                        }
                        val from = journey.points[index - 1]
                        val to = journey.points[index]
                        val sourceKind = when {
                            from in semanticPoints && to in semanticPoints -> "SEMANTIC"
                            from in detailedPoints && to in detailedPoints -> "DETAILED"
                            else -> "BETWEEN_SOURCES"
                        }
                        anomalySourceCounts[sourceKind] = anomalySourceCounts.getOrDefault(sourceKind, 0) + 1
                    }
                }
                // Sensitivity only. Production route and analyzer remain unchanged.
                val sensitivity = RecapAnalyzer.analyze(journey.copy(
                    inferredTransferBeforePointIndices = (journey.inferredTransferBeforePointIndices + diagnosticExcludedIndices).distinct().sorted(),
                ), zone)
                sensitivityPersonalities[sensitivity.personalityId.name] = sensitivityPersonalities.getOrDefault(sensitivity.personalityId.name, 0) + 1
                if (sensitivity.personalityId != recap.personalityId) sensitivityChangedMonths++
                month = month.plusMonths(1)
            }
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val afterHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("Source bytes remain unchanged", import.sourceHash, afterHash)
            val evidence = JSONObject()
                .put("pipeline", "TimelineJournalImportAdapter -> JournalRepository -> JournalRouteService -> RecapAnalyzer")
                .put("routeDetail", routeDetail.name)
                .put("sourceSha256", afterHash)
                .put("firstMonth", first.toString()).put("lastMonth", last.toString()).put("zoneId", zone.id)
                .put("evaluatedMonths", count).put("monthsWithoutDistance", empty).put("analyzedPoints", analyzedPoints)
                .put("personalityCounts", JSONObject(personalities as Map<*, *>))
                .put("transportModeCounts", JSONObject(modes as Map<*, *>))
                .put("totalKnownDistanceKm", knownKm).put("distanceInSpansOverSixHoursKm", longSpanKm)
                .put("segmentsOver2000KmhOrNonPositiveDuration", extremeSpeedSegments)
                .put("distanceInThoseSegmentsKm", extremeSpeedKm)
                .put("zeroDurationSegments", zeroDurationSegments).put("zeroDurationDistanceKm", zeroDurationKm)
                .put("negativeDurationSegments", negativeDurationSegments)
                .put("positiveDurationOver2000KmhSegments", positiveDurationExtremeSegments)
                .put("positiveDurationOver2000KmhDistanceKm", positiveDurationExtremeKm)
                .put("anomalySourceCounts", JSONObject(anomalySourceCounts as Map<*, *>))
                .put("sensitivityExcludingAnomaliesPersonalityCounts", JSONObject(sensitivityPersonalities as Map<*, *>))
                .put("sensitivityChangedPersonalityMonths", sensitivityChangedMonths)
                .put("chaosMonthsWithPeakShareAtLeast35Percent", chaosMonthsWithPeakShareAtLeast35Percent)
                .put("notes", "One user's monthly windows test repeatability, not population diversity. Distance follows the production route. Unknown and conflicting transport labels remain unknown. No place or inactivity inference is used.")
            val output = File(requireNotNull(outputPath)).apply { mkdirs() }
            File(output, "recap-private-aggregate.json").writeText(evidence.toString(2) + "\n")
        } finally {
            database.close()
        }
    }
}
