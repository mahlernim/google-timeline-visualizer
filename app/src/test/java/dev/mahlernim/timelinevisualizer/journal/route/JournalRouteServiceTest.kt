package dev.mahlernim.timelinevisualizer.journal.route

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.journal.DetailedObservationInput
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalImport
import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.journal.SemanticSegmentInput
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalRouteServiceTest {
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository
    private lateinit var service: JournalRouteService

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ids = generateSequence(1) { it + 1 }.map { "route-$it" }.iterator()
        repository = JournalRepository(database) { ids.next() }
        service = JournalRouteService(repository)
        repository.createJournal(
            JournalEntity(
                id = JOURNAL_ID,
                name = "My Journal",
                isPrimary = true,
                createdAtEpochMillis = BASE.toEpochMilli(),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun detailedIsCanonicalAndSemanticFillsTheUncoveredInterval() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hybrid",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
                semantic = listOf(semantic(0, 60, listOf(point(0, 9.0), point(30, 9.3), point(60, 9.6)))),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(61)))

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.SEMANTIC_PATH),
            route.spans.map(RouteSpan::source),
        )
        assertEquals(listOf(1.0, 1.1, 9.3, 9.6), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun simplifiedRouteUsesSemanticHistoryInsteadOfOverlappingDetailedPoints() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "simplified",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
                semantic = listOf(semantic(0, 60, listOf(point(0, 9.0), point(30, 9.3), point(60, 9.6)))),
            ),
        )

        val route = service.route(
            JOURNAL_ID,
            BASE,
            BASE.plus(Duration.ofMinutes(61)),
            routeDetail = RouteDetail.SIMPLIFIED,
        )

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), route.spans.map(RouteSpan::source))
        assertEquals(listOf(9.0, 9.3, 9.6), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun simplifiedRouteFallsBackToDetailedHistoryWhenSemanticHistoryIsMissing() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "detailed-fallback",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
            ),
        )

        val route = service.route(
            JOURNAL_ID,
            BASE,
            BASE.plus(Duration.ofMinutes(11)),
            routeDetail = RouteDetail.SIMPLIFIED,
        )

        assertEquals(listOf(RouteSource.DETAILED), route.spans.map(RouteSpan::source))
        assertEquals(listOf(1.0, 1.1), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun detailedDiscontinuityWithoutSemanticCoverageBecomesAnInferredTransfer() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "gapped",
                importedAt = minute(100),
                observations = listOf(
                    observation(0, 1.0),
                    observation(10, 1.1),
                    observation(50, 2.0),
                    observation(60, 2.1),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(61)))

        assertEquals(
            listOf(RouteSource.DETAILED, RouteSource.INFERRED_TRANSFER, RouteSource.DETAILED),
            route.spans.map(RouteSpan::source),
        )
        assertEquals("Inferred between detailed observation islands", route.spans[1].transitionReason)
    }

    @Test
    fun newerPartialSemanticSnapshotWinsOnlyInsideItsCoverage() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "old-semantic",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 40, listOf(point(0, 1.0), point(10, 1.1), point(20, 1.2), point(30, 1.3), point(40, 1.4))),
                ),
            ),
        )
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "new-partial",
                importedAt = minute(200),
                semantic = listOf(semantic(20, 30, listOf(point(20, 5.2), point(30, 5.3)))),
            ),
        )

        val captures = repository.activeSemanticSegments(JOURNAL_ID, minute(0), minute(41))
            .map { it.snapshotCapturedAtEpochMillis }
            .distinct()
        assertEquals(listOf(minute(40) + 1, minute(40)), captures)

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(41)))

        assertEquals(listOf(1.0, 1.1, 5.2, 5.3, 1.4), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun appendOnlyImportsReconstructEarlierAndNewDetailWithConcreteOverlap() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "first-window",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
            ),
        )
        val nextWindow = listOf(observation(10, 1.1), observation(20, 1.2))
        assertEquals(1, repository.detailedOverlapCount(JOURNAL_ID, nextWindow))
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "second-window",
                importedAt = minute(200),
                observations = nextWindow,
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(21)))

        assertEquals(listOf(1.0, 1.1, 1.2), route.timeline.points.map(GeoPoint::latitude))
        assertEquals(3, route.detailedInputCount)
        assertNotNull(repository.primaryJournal())
    }

    @Test
    fun adjacentStructuredSemanticRecordsUseAnInferredVideoTransition() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "independent-segments",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 10, listOf(point(0, 1.0), point(10, 1.1))),
                    semantic(10, 20, listOf(point(10, 2.0), point(20, 2.1))),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(21)))

        assertEquals(
            listOf(RouteSource.SEMANTIC_PATH, RouteSource.INFERRED_TRANSFER, RouteSource.SEMANTIC_PATH),
            route.spans.map(RouteSpan::source),
        )
        assertEquals("Inferred between available Timeline records", route.spans[1].transitionReason)
    }

    @Test
    fun legacyParserChunksRemainOneConnectedCompatibilityPath() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "legacy-chunks",
                importedAt = minute(100),
                parserVersion = 1,
                semantic = listOf(
                    semantic(0, 10, listOf(point(0, 1.0), point(10, 1.1))),
                    semantic(20, 30, listOf(point(20, 1.2), point(30, 1.3))),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), route.spans.map(RouteSpan::source))
        assertEquals(listOf(1.0, 1.1, 1.2, 1.3), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun boundedLegacyRowsMatchClippedFullReconstructionWithoutSnapshotExpansion() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "legacy-bounded",
                importedAt = minute(200),
                parserVersion = 1,
                semantic = (0 until 100).map { index ->
                    val start = index * 10L
                    semantic(
                        start,
                        start + 9,
                        listOf(point(start, index.toDouble()), point(start + 9, index + 0.5)),
                    )
                },
            ),
        )
        val requestStart = Instant.ofEpochMilli(minute(450))
        val requestEnd = Instant.ofEpochMilli(minute(480))
        val full = service.route(
            JOURNAL_ID,
            BASE,
            Instant.ofEpochMilli(minute(1_000)),
            maximumAccuracyMeters = null,
        )

        val bounded = service.route(
            JOURNAL_ID,
            requestStart,
            requestEnd,
            maximumAccuracyMeters = null,
        )

        assertEquals(full.clippedTo(requestStart, requestEnd).timeline.points, bounded.timeline.points)
        assertEquals(full.clippedTo(requestStart, requestEnd).spans, bounded.spans)
    }

    @Test
    fun explicitPartsOfOneStructuredRecordRemainConnected() = runBlocking {
        val first = listOf(point(0, 1.0), point(10, 1.1))
        val second = listOf(point(20, 1.2), point(30, 1.3))
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "structured-parts",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 10, first).copy(
                        geometryJson = SemanticGeometryCodec.encodePart(first, "source:7", 0, 2),
                    ),
                    semantic(20, 30, second).copy(
                        geometryJson = SemanticGeometryCodec.encodePart(second, "source:7", 1, 2),
                    ),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(RouteSource.SEMANTIC_PATH), route.spans.map(RouteSpan::source))
    }

    @Test
    fun structuredActivityCoverageSuppressesOverlappingStandalonePathHistory() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "secondary-path",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(10, 20, listOf(point(10, 5.0), point(20, 5.1))),
                    semantic(0, 30, listOf(point(0, 9.0), point(10, 9.1), point(20, 9.2), point(30, 9.3)))
                        .copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(9.0, 5.0, 5.1, 9.3), route.timeline.points.map(GeoPoint::latitude))
        assertEquals(2, route.spans.count { it.source == RouteSource.INFERRED_TRANSFER })
    }

    @Test
    fun complementaryStandalonePathRetainsLegitimateCurvedShape() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "curved-path",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 30, listOf(point(0, 1.0), point(30, 1.6))),
                    semantic(
                        0,
                        30,
                        listOf(point(0, 1.0), point(10, 1.4), point(20, 1.2), point(30, 1.6)),
                    ).copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(1.0, 1.4, 1.2, 1.6), route.timeline.points.map(GeoPoint::latitude))
        assertEquals(listOf(RouteSource.SEMANTIC_PATH), route.spans.map(RouteSpan::source))
    }

    @Test
    fun duplicateSemanticHistoriesAreSelectedOnceWithoutBacktracking() = runBlocking {
        val geometry = listOf(point(0, 4.0), point(10, 4.2), point(20, 4.4), point(30, 4.6))
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "duplicate-history",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 30, geometry),
                    semantic(0, 30, geometry).copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(4.0, 4.2, 4.4, 4.6), route.timeline.points.map(GeoPoint::latitude))
        assertEquals(1, route.spans.size)
    }

    @Test
    fun reversedStandaloneHistoryIsRejectedInsideSemanticComponents() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "reversed-history",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 10, listOf(point(0, 10.0), point(10, 20.0))),
                    semantic(20, 30, listOf(point(20, 20.0), point(30, 10.0))),
                    semantic(
                        0,
                        31,
                        listOf(
                            point(5, 20.0),
                            point(8, 10.0),
                            point(25, 10.0),
                            point(28, 20.0),
                            point(31, 30.0),
                        ),
                    ).copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(32)))

        assertEquals(listOf(10.0, 20.0, 20.0, 10.0, 30.0), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun boundedReconciliationExpandsSplitComponentsBeforeDirectionArbitration() = runBlocking {
        val activityParts = listOf(
            listOf(point(0, 10.0)),
            listOf(point(55, 14.0), point(65, 16.0)),
            listOf(point(120, 20.0)),
        )
        val reversedPathParts = listOf(
            listOf(point(0, 20.0)),
            listOf(point(55, 30.0), point(65, 31.0)),
            listOf(point(120, 10.0)),
        )
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "split-reversal-anchors",
                importedAt = minute(200),
                semantic = activityParts.mapIndexed { index, points ->
                    semantic(
                        Duration.between(BASE, points.first().instant).toMinutes(),
                        Duration.between(BASE, points.last().instant).toMinutes(),
                        points,
                    ).copy(geometryJson = SemanticGeometryCodec.encodePart(points, "activity:split", index, 3))
                } + reversedPathParts.mapIndexed { index, points ->
                    semantic(
                        Duration.between(BASE, points.first().instant).toMinutes(),
                        Duration.between(BASE, points.last().instant).toMinutes(),
                        points,
                    ).copy(
                        kind = "PATH",
                        geometryJson = SemanticGeometryCodec.encodePart(points, "path:split", index, 3),
                    )
                },
            ),
        )
        val requestStart = BASE.plus(Duration.ofMinutes(50))
        val requestEnd = BASE.plus(Duration.ofMinutes(71))
        val full = service.route(
            JOURNAL_ID,
            BASE,
            BASE.plus(Duration.ofMinutes(121)),
            maximumAccuracyMeters = null,
        )

        val bounded = service.route(
            JOURNAL_ID,
            requestStart,
            requestEnd,
            maximumAccuracyMeters = null,
        )

        val expected = full.clippedTo(requestStart, requestEnd)
        assertEquals(expected.timeline.points, bounded.timeline.points)
        assertEquals(expected.spans, bounded.spans)
        assertEquals(listOf(14.0, 16.0), bounded.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun genuineReturnAndUTurnGeometryIsNotClassifiedAsBacktracking() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "real-return",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 30, listOf(point(0, 3.0), point(30, 3.0))),
                    semantic(
                        0,
                        30,
                        listOf(point(0, 3.0), point(10, 3.5), point(20, 3.2), point(30, 3.0)),
                    ).copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(31)))

        assertEquals(listOf(3.0, 3.5, 3.2, 3.0), route.timeline.points.map(GeoPoint::latitude))
    }

    @Test
    fun reverseOrderedComplementaryPathsPreserveChronologicalOutput() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "reverse-ordered-paths",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 10, listOf(point(0, 1.0), point(10, 1.5))),
                    semantic(20, 30, listOf(point(20, 2.0), point(30, 2.5))),
                    semantic(40, 50, listOf(point(40, 3.0), point(50, 3.5))),
                    semantic(40, 50, listOf(point(40, 3.0), point(50, 3.5))).copy(kind = "PATH"),
                    semantic(20, 30, listOf(point(20, 2.0), point(30, 2.5))).copy(kind = "PATH"),
                    semantic(0, 10, listOf(point(0, 1.0), point(10, 1.5))).copy(kind = "PATH"),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(51)))

        assertEquals(
            listOf(1.0, 1.5, 2.0, 2.5, 3.0, 3.5),
            route.timeline.points.map(GeoPoint::latitude),
        )
    }

    @Test
    fun boundedProjectionRefreshMatchesFullReconstructionAfterDetailedUpdate() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "projection-base",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(0, 60, listOf(point(0, 1.0), point(60, 1.6))),
                    semantic(
                        0,
                        60,
                        listOf(point(0, 1.0), point(20, 1.2), point(40, 1.4), point(60, 1.6)),
                    ).copy(kind = "PATH"),
                ),
            ),
        )
        service.route(JOURNAL_ID, LIFETIME_START, LIFETIME_END)
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "projection-detail",
                importedAt = minute(200),
                observations = listOf(observation(20, 7.2), observation(30, 7.3), observation(40, 7.4)),
            ),
        )

        val incrementallyRebuilt = service.route(JOURNAL_ID, LIFETIME_START, LIFETIME_END)
        val fullyReconstructed = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(61)))

        assertEquals(fullyReconstructed.timeline.points, incrementallyRebuilt.timeline.points)
        assertEquals(fullyReconstructed.spans, incrementallyRebuilt.spans)
    }

    @Test
    fun severalNewerCoverageIntervalsSplitOlderHistoryAtEveryBoundary() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "old-complete",
                importedAt = minute(100),
                semantic = listOf(
                    semantic(
                        0,
                        60,
                        (0L..60L step 10L).mapIndexed { index, value -> point(value, index.toDouble()) },
                    ),
                ),
            ),
        )
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "new-islands",
                importedAt = minute(200),
                semantic = listOf(
                    semantic(10, 20, listOf(point(10, 11.0), point(20, 12.0))),
                    semantic(40, 50, listOf(point(40, 14.0), point(50, 15.0))),
                ),
            ),
        )

        val route = service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(61)))

        assertEquals(
            listOf(0.0, 11.0, 12.0, 3.0, 14.0, 15.0, 6.0),
            route.timeline.points.map(GeoPoint::latitude),
        )
    }

    @Test
    fun canonicalProjectionReportsOnlyExistingPreparationBoundaries() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "progress",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
                semantic = listOf(semantic(0, 30, listOf(point(0, 9.0), point(30, 9.3)))),
            ),
        )
        val stages = mutableListOf<JournalRoutePreparationStage>()

        service.route(
            journalId = JOURNAL_ID,
            start = Instant.ofEpochMilli(Long.MIN_VALUE),
            endExclusive = Instant.ofEpochMilli(Long.MAX_VALUE),
            onPreparationStage = stages::add,
        )

        assertEquals(
            listOf(
                JournalRoutePreparationStage.PREPARING_DETAILED_ROUTES,
                JournalRoutePreparationStage.COMBINING_JOURNEY_HISTORY,
                JournalRoutePreparationStage.SAVING_FOR_FASTER_STARTS,
            ),
            stages,
        )
    }

    @Test
    fun cachedProjectionDoesNotReportReconstructionProgress() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "cached-progress",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
            ),
        )
        service.route(
            journalId = JOURNAL_ID,
            start = Instant.ofEpochMilli(Long.MIN_VALUE),
            endExclusive = Instant.ofEpochMilli(Long.MAX_VALUE),
        )
        val stages = mutableListOf<JournalRoutePreparationStage>()

        service.route(
            journalId = JOURNAL_ID,
            start = Instant.ofEpochMilli(Long.MIN_VALUE),
            endExclusive = Instant.ofEpochMilli(Long.MAX_VALUE),
            onPreparationStage = stages::add,
        )

        assertEquals(emptyList<JournalRoutePreparationStage>(), stages)
    }

    @Test
    fun boundedProjectionServesAContainedRangeWithoutReconstruction() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "bounded-cache",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1), observation(20, 1.2), observation(30, 1.3)),
            ),
        )
        val cacheStart = BASE.plus(Duration.ofMinutes(5))
        val cacheEnd = BASE.plus(Duration.ofMinutes(26))
        service.route(JOURNAL_ID, cacheStart, cacheEnd)
        val stages = mutableListOf<JournalRoutePreparationStage>()

        val contained = service.route(
            journalId = JOURNAL_ID,
            start = BASE.plus(Duration.ofMinutes(10)),
            endExclusive = BASE.plus(Duration.ofMinutes(21)),
            onPreparationStage = stages::add,
        )

        assertEquals(listOf(1.1, 1.2), contained.timeline.points.map(GeoPoint::latitude))
        assertEquals(emptyList<JournalRoutePreparationStage>(), stages)
        val state = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals(cacheStart.toEpochMilli(), state.projectionStartEpochMillis)
        assertEquals(cacheEnd.toEpochMilli(), state.projectionEndExclusiveEpochMillis)
    }

    @Test
    fun touchingRequestsExtendCacheAndBothRangesRemainReusable() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "two-ranges",
                importedAt = minute(100),
                observations = listOf(
                    observation(0, 1.0),
                    observation(10, 1.1),
                    observation(20, 1.2),
                    observation(30, 1.3),
                    observation(40, 1.4),
                ),
            ),
        )
        val middle = BASE.plus(Duration.ofMinutes(21))
        val end = BASE.plus(Duration.ofMinutes(41))
        service.route(JOURNAL_ID, BASE, middle)
        service.route(JOURNAL_ID, middle, end)
        val stages = mutableListOf<JournalRoutePreparationStage>()

        val firstRangeAgain = service.route(
            JOURNAL_ID,
            BASE,
            middle,
            onPreparationStage = stages::add,
        )

        assertEquals(listOf(1.0, 1.1, 1.2), firstRangeAgain.timeline.points.map(GeoPoint::latitude))
        assertEquals(emptyList<JournalRoutePreparationStage>(), stages)
        val state = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals(BASE.toEpochMilli(), state.projectionStartEpochMillis)
        assertEquals(end.toEpochMilli(), state.projectionEndExclusiveEpochMillis)
    }

    @Test
    fun dirtyChangeOutsideRequestedCachedRangeDoesNotForceARebuild() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "bounded-base",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1), observation(20, 1.2)),
            ),
        )
        service.route(JOURNAL_ID, BASE, BASE.plus(Duration.ofMinutes(21)))
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "outside-change",
                importedAt = minute(200),
                observations = listOf(observation(40, 4.0)),
            ),
        )
        val stages = mutableListOf<JournalRoutePreparationStage>()

        val cached = service.route(
            journalId = JOURNAL_ID,
            start = BASE,
            endExclusive = BASE.plus(Duration.ofMinutes(21)),
            onPreparationStage = stages::add,
        )

        assertEquals(listOf(1.0, 1.1, 1.2), cached.timeline.points.map(GeoPoint::latitude))
        assertEquals(emptyList<JournalRoutePreparationStage>(), stages)
        assertEquals("DIRTY", database.journalDao().routeProjectionState(JOURNAL_ID)?.buildStatus)
    }

    @Test
    fun dirtyChangeInsideCachedRangeRebuildsAndPersistsTheWindow() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "inside-base",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1), observation(20, 1.2)),
            ),
        )
        val rangeEnd = BASE.plus(Duration.ofMinutes(21))
        service.route(JOURNAL_ID, BASE, rangeEnd)
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "inside-change",
                importedAt = minute(200),
                observations = listOf(observation(15, 7.5)),
            ),
        )
        val stages = mutableListOf<JournalRoutePreparationStage>()

        val rebuilt = service.route(
            journalId = JOURNAL_ID,
            start = BASE,
            endExclusive = rangeEnd,
            onPreparationStage = stages::add,
        )

        assertEquals(listOf(1.0, 1.1, 7.5, 1.2), rebuilt.timeline.points.map(GeoPoint::latitude))
        assertEquals(JournalRoutePreparationStage.SAVING_FOR_FASTER_STARTS, stages.last())
        val state = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals("READY", state.buildStatus)
        assertEquals(BASE.toEpochMilli(), state.projectionStartEpochMillis)
        assertEquals(rangeEnd.toEpochMilli(), state.projectionEndExclusiveEpochMillis)
    }

    @Test
    fun sourceRevisionChangeWhileSavingCancelsTheStaleRouteBuild() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "stale-build-base",
                importedAt = minute(100),
                observations = listOf(observation(0, 1.0), observation(10, 1.1)),
            ),
        )
        var failure: Throwable? = null
        var advancedSource = false

        try {
            service.route(
                JOURNAL_ID,
                BASE,
                BASE.plus(Duration.ofMinutes(11)),
                onPreparationStage = { stage ->
                    if (stage == JournalRoutePreparationStage.SAVING_FOR_FASTER_STARTS && !advancedSource) {
                        advancedSource = true
                        repository.import(
                            JOURNAL_ID,
                            importInput(
                                hash = "stale-build-update",
                                importedAt = minute(200),
                                observations = listOf(observation(20, 2.0)),
                            ),
                        )
                    }
                },
            )
        } catch (caught: Throwable) {
            failure = caught
        }

        assertEquals(StaleJournalRouteBuildException::class.java, failure?.javaClass)
        val state = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals("DIRTY", state.buildStatus)
        assertEquals(2L, state.sourceRevision)
        assertEquals(0L, state.builtRevision)
        assertEquals(null, JournalRouteProjectionStore(database).read(JOURNAL_ID)?.route)
    }

    private fun importInput(
        hash: String,
        importedAt: Long,
        observations: List<DetailedObservationInput> = emptyList(),
        semantic: List<SemanticSegmentInput> = emptyList(),
        parserVersion: Int = 2,
    ) = JournalImport(
        sourceHash = hash,
        sourceName = "timeline.json",
        sourceSize = 1_024,
        importedAtEpochMillis = importedAt,
        parserVersion = parserVersion,
        matchClassification = JournalMatchClassification.LIKELY_SAME,
        detailedObservations = observations,
        semanticSegments = semantic,
    )

    private fun observation(minutes: Long, latitude: Double) = DetailedObservationInput(
        instantEpochMillis = minute(minutes),
        latitude = latitude,
        longitude = 127.0 + latitude,
        accuracyMeters = 10.0,
    )

    private fun semantic(
        startMinutes: Long,
        endMinutes: Long,
        points: List<GeoPoint>,
    ) = SemanticSegmentInput(
        startEpochMillis = minute(startMinutes),
        endEpochMillis = minute(endMinutes),
        kind = "ACTIVITY",
        geometryJson = SemanticGeometryCodec.encode(points),
    )

    private fun point(minutes: Long, latitude: Double) = GeoPoint(
        instant = BASE.plus(Duration.ofMinutes(minutes)),
        latitude = latitude,
        longitude = 127.0 + latitude,
    )

    private fun minute(value: Long): Long = BASE.plus(Duration.ofMinutes(value)).toEpochMilli()

    private companion object {
        const val JOURNAL_ID = "journal-route-test"
        val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val LIFETIME_START: Instant = Instant.ofEpochMilli(Long.MIN_VALUE)
        val LIFETIME_END: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
    }
}
