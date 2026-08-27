package dev.mahlernim.timelinevisualizer.journal.route

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.journal.DetailedObservationInput
import dev.mahlernim.timelinevisualizer.journal.JournalDatabase
import dev.mahlernim.timelinevisualizer.journal.JournalEntity
import dev.mahlernim.timelinevisualizer.journal.JournalImport
import dev.mahlernim.timelinevisualizer.journal.JournalImportResult
import dev.mahlernim.timelinevisualizer.journal.JournalMatchClassification
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalRouteProjectionStoreTest {
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = JournalRepository(database) { "id-${System.nanoTime()}" }
        repository.createJournal(
            JournalEntity(JOURNAL_ID, "Journal", true, 1L),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun binaryChunksRoundTripAndStaleReplacementCannotOverwriteNewSource() = runBlocking {
        import("first", listOf(observation(1)))
        val state = repository.ensureRouteProjectionState(JOURNAL_ID)
        val points = (0..1_024).map { index ->
            GeoPoint(Instant.ofEpochMilli(index.toLong()), 30.0 + index / 10_000.0, 120.0)
        }
        val route = JournalRoute(
            timeline = dev.mahlernim.timelinevisualizer.model.Timeline(points),
            spans = listOf(RouteSpan(points.first().instant, points.last().instant, RouteSource.DETAILED, points)),
            detailedInputCount = points.size,
            detailedUsableCount = points.size,
            semanticUsableCount = 0,
        )
        val store = JournalRouteProjectionStore(database)

        assertTrue(store.replace(JOURNAL_ID, state.sourceRevision, 1, route, 100L))
        val stored = requireNotNull(store.read(JOURNAL_ID))
        assertEquals(points, requireNotNull(stored.route).timeline.points)
        assertEquals(2, database.journalDao().routeProjectionChunks(
            database.journalDao().routeProjectionSpans(JOURNAL_ID).map { it.id },
        ).size)

        import("second", listOf(observation(2)))
        assertFalse(store.replace(JOURNAL_ID, state.sourceRevision, 1, route, 200L))
        assertEquals("DIRTY", database.journalDao().routeProjectionState(JOURNAL_ID)?.buildStatus)
    }

    @Test
    fun lifetimeRoutePersistsThenIncrementallyAdvancesDirtyProjection() = runBlocking {
        val first = import("first", listOf(observation(10), observation(20)))
        assertTrue((first as JournalImportResult.Committed).needsRouteRefresh)
        val service = JournalRouteService(repository)

        val initial = service.route(JOURNAL_ID, FULL_START, FULL_END)
        val ready = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals(ready.sourceRevision, ready.builtRevision)
        assertEquals("READY", ready.buildStatus)
        assertEquals(initial, service.route(JOURNAL_ID, FULL_START, FULL_END))

        val duplicate = import("first", listOf(observation(10), observation(20)))
        assertTrue(duplicate is JournalImportResult.AlreadyImported)
        assertEquals(ready, database.journalDao().routeProjectionState(JOURNAL_ID))

        import("next", listOf(observation(20), observation(30)))
        val dirty = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals(ready.sourceRevision + 1, dirty.sourceRevision)
        assertEquals("DIRTY", dirty.buildStatus)
        val updated = service.route(JOURNAL_ID, FULL_START, FULL_END)
        assertEquals(listOf(10L, 20L, 30L), updated.timeline.points.map { it.instant.toEpochMilli() })
        val rebuilt = requireNotNull(database.journalDao().routeProjectionState(JOURNAL_ID))
        assertEquals(rebuilt.sourceRevision, rebuilt.builtRevision)
        assertEquals("READY", rebuilt.buildStatus)
    }

    @Test
    fun boundedProjectionPersistsItsLogicalCoverageAndRoundTrips() = runBlocking {
        import("bounded", listOf(observation(10), observation(20), observation(30)))
        val state = repository.ensureRouteProjectionState(JOURNAL_ID)
        val points = listOf(10L, 20L, 30L).map { instant ->
            GeoPoint(Instant.ofEpochMilli(instant), 37.0 + instant / 1_000.0, 127.0)
        }
        val route = JournalRoute(
            timeline = dev.mahlernim.timelinevisualizer.model.Timeline(points),
            spans = listOf(RouteSpan(points.first().instant, points.last().instant, RouteSource.DETAILED, points)),
            detailedInputCount = 3,
            detailedUsableCount = 3,
            semanticUsableCount = 0,
        )
        val store = JournalRouteProjectionStore(database)

        assertTrue(
            store.replace(
                journalId = JOURNAL_ID,
                expectedSourceRevision = state.sourceRevision,
                algorithmVersion = 1,
                route = route,
                projectionStartEpochMillis = 10L,
                projectionEndExclusiveEpochMillis = 31L,
            ),
        )

        val stored = requireNotNull(store.read(JOURNAL_ID))
        assertEquals(10L, stored.state.projectionStartEpochMillis)
        assertEquals(31L, stored.state.projectionEndExclusiveEpochMillis)
        assertEquals(route, stored.route)
    }

    @Test
    fun boundedReadSelectsOnlyContributingBinaryChunks() = runBlocking {
        import("chunk-range", listOf(observation(1)))
        val state = repository.ensureRouteProjectionState(JOURNAL_ID)
        val points = (0..3_071).map { index ->
            GeoPoint(Instant.ofEpochMilli(index.toLong()), 37.0 + index / 100_000.0, 127.0)
        }
        val route = JournalRoute(
            timeline = dev.mahlernim.timelinevisualizer.model.Timeline(points),
            spans = listOf(RouteSpan(points.first().instant, points.last().instant, RouteSource.DETAILED, points)),
            detailedInputCount = points.size,
            detailedUsableCount = points.size,
            semanticUsableCount = 0,
        )
        val store = JournalRouteProjectionStore(database)
        assertTrue(store.replace(JOURNAL_ID, state.sourceRevision, 1, route, 0L, 3_072L))

        val spanId = database.journalDao().routeProjectionSpans(JOURNAL_ID).single().id
        val selectedChunks = database.journalDao().routeProjectionChunksInRange(
            listOf(spanId),
            1_024L,
            2_048L,
        )
        assertEquals(1, selectedChunks.size)

        val bounded = requireNotNull(
            store.read(JOURNAL_ID, Instant.ofEpochMilli(1_024L), Instant.ofEpochMilli(2_048L))?.route,
        )
        assertEquals((1_024L until 2_048L).toList(), bounded.timeline.points.map { it.instant.toEpochMilli() })
    }

    private suspend fun import(hash: String, observations: List<DetailedObservationInput>) = repository.import(
        JOURNAL_ID,
        JournalImport(
            sourceHash = hash,
            sourceName = "$hash.json",
            sourceSize = 1,
            importedAtEpochMillis = 1_000,
            parserVersion = 2,
            matchClassification = JournalMatchClassification.LIKELY_SAME,
            detailedObservations = observations,
            semanticSegments = emptyList(),
        ),
    )

    private fun observation(instant: Long) = DetailedObservationInput(
        instantEpochMillis = instant,
        latitude = 37.0 + instant / 1_000.0,
        longitude = 127.0,
        accuracyMeters = 5.0,
    )

    private companion object {
        const val JOURNAL_ID = "journal"
        val FULL_START: Instant = Instant.ofEpochMilli(Long.MIN_VALUE)
        val FULL_END: Instant = Instant.ofEpochMilli(Long.MAX_VALUE)
    }
}
