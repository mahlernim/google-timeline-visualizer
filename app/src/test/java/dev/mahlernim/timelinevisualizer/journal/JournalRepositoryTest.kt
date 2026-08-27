package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalRepositoryTest {
    private lateinit var database: JournalDatabase
    private lateinit var repository: JournalRepository
    private lateinit var dao: JournalDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, JournalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.journalDao()
        val ids = generateSequence(1) { it + 1 }.map { "generated-$it" }.iterator()
        repository = JournalRepository(database) { ids.next() }
        runBlocking {
            repository.createJournal(
                JournalEntity(
                    id = JOURNAL_ID,
                    name = "My Journal",
                    isPrimary = true,
                    createdAtEpochMillis = 1_000,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun repeatedFileIsNoOpAndRedundantObservationProvenanceIsSkipped() = runBlocking {
        val first = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hash-one",
                importedAt = 10_000,
                observations = listOf(observation(2_000), observation(3_000)),
            ),
        ) as JournalImportResult.Committed

        assertEquals(2, first.insertedObservationCount)
        assertEquals(0, first.duplicateObservationCount)
        assertEquals(2, dao.observationCount(JOURNAL_ID))
        assertEquals(2, dao.provenanceCount(first.batchId))

        val duplicateFile = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hash-one",
                importedAt = 11_000,
                observations = listOf(observation(2_000), observation(3_000)),
            ).copy(matchClassification = JournalMatchClassification.UNCERTAIN),
        )
        assertEquals(JournalImportResult.AlreadyImported(first.batchId), duplicateFile)
        assertEquals(first.batchId, repository.committedImport(JOURNAL_ID, "hash-one")?.id)
        assertEquals(2, dao.observationCount(JOURNAL_ID))

        val overlapping = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "hash-two",
                importedAt = 12_000,
                observations = listOf(observation(3_000), observation(4_000)),
            ),
        ) as JournalImportResult.Committed
        assertEquals(1, overlapping.insertedObservationCount)
        assertEquals(1, overlapping.duplicateObservationCount)
        assertEquals(3, dao.observationCount(JOURNAL_ID))
        assertEquals(1, dao.provenanceCount(overlapping.batchId))
        assertEquals(JournalImportResult.ChangeKind.ADVANCED, overlapping.changeKind)
        assertEquals(4_000L, overlapping.changedStartEpochMillis)
        assertEquals(4_000L, overlapping.changedEndEpochMillis)
        assertEquals(4_000L, dao.journal(JOURNAL_ID)?.detailedCapturedThroughEpochMillis)
        assertEquals(12_000L, dao.journal(JOURNAL_ID)?.lastAdvancedAtEpochMillis)
    }

    @Test
    fun betterDuplicateAccuracyIsRetainedButWorseAccuracyIsNot() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput("base", 10_000, listOf(observation(2_000, accuracy = 12.0))),
        )

        val worse = repository.import(
            JOURNAL_ID,
            importInput("worse", 11_000, listOf(observation(2_000, accuracy = 20.0))),
        ) as JournalImportResult.Committed
        val better = repository.import(
            JOURNAL_ID,
            importInput("better", 12_000, listOf(observation(2_000, accuracy = 5.0))),
        ) as JournalImportResult.Committed

        assertEquals(0, dao.provenanceCount(worse.batchId))
        assertEquals(false, worse.needsRouteRefresh)
        assertEquals(1, dao.provenanceCount(better.batchId))
        assertEquals(true, better.needsRouteRefresh)
        assertEquals(2_000L, better.changedStartEpochMillis)
        assertEquals(2_000L, better.changedEndEpochMillis)
        assertEquals(5.0, repository.activeDetailedObservations(JOURNAL_ID, 0, 3_000).single().accuracyMeters, 0.0)
    }

    @Test
    fun importAdvancesFreshnessOnlyThroughCanonicallyUsableDetail() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                "freshness",
                10_000,
                listOf(
                    observation(2_000, accuracy = 5.0),
                    observation(3_000, accuracy = 150.0),
                ),
            ),
        )

        val journal = requireNotNull(repository.journal(JOURNAL_ID))
        assertEquals(3_000L, journal.detailedCapturedThroughEpochMillis)
        assertEquals(2_000L, journal.detailedUsableThroughEpochMillis)
    }

    @Test
    fun latestUsableDetailScansNewestObservationsThroughTheTimeIndex() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                "freshness-plan",
                10_000,
                listOf(
                    observation(2_000, accuracy = 5.0),
                    observation(3_000, accuracy = 150.0),
                ),
            ),
        )

        assertEquals(2_000L, requireNotNull(dao.latestUsableDetailedEpochMillis(JOURNAL_ID, 100.0)))
        val plan = database.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                """
                EXPLAIN QUERY PLAN
                SELECT detailed_observations.instantEpochMillis
                FROM detailed_observations
                WHERE detailed_observations.journalId = ?
                  AND (
                      SELECT MIN(observation_imports.accuracyMeters)
                      FROM observation_imports
                      INNER JOIN import_batches
                          ON import_batches.id = observation_imports.importBatchId
                      WHERE observation_imports.observationId = detailed_observations.id
                        AND import_batches.status = 'COMMITTED'
                        AND observation_imports.accuracyMeters IS NOT NULL
                  ) <= ?
                ORDER BY detailed_observations.instantEpochMillis DESC,
                         detailed_observations.id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(JOURNAL_ID, 100.0),
            ),
        ).use { cursor ->
            val details = mutableListOf<String>()
            val detailColumn = cursor.getColumnIndexOrThrow("detail")
            while (cursor.moveToNext()) details += cursor.getString(detailColumn)
            details
        }
        assertTrue(plan.any { it.contains("index_detailed_observations_journalId_instantEpochMillis") })
        assertTrue(plan.any { it.contains("index_observation_imports_observationId") })
        assertTrue(plan.none { it.contains("USE TEMP B-TREE FOR ORDER BY") })
    }

    @Test
    fun olderImportIsBackfillAndCannotOutrankNewerSemanticCoverage() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "newer-export",
                importedAt = 10_000,
                observations = listOf(observation(8_000), observation(10_000)),
                segments = listOf(segment(8_000, 10_000, "VISIT", activityType = "NEWER")),
            ),
        )

        val backfill = repository.import(
            JOURNAL_ID,
            importInput(
                hash = "older-export-imported-later",
                importedAt = 30_000,
                observations = listOf(observation(2_000), observation(5_000)),
                segments = listOf(segment(2_000, 9_000, "VISIT", activityType = "OLDER")),
            ),
        ) as JournalImportResult.Committed

        assertEquals(JournalImportResult.ChangeKind.BACKFILL, backfill.changeKind)
        assertEquals(2_000L, backfill.changedStartEpochMillis)
        assertEquals(8_000L, backfill.changedEndEpochMillis)
        assertEquals(true, backfill.activeSemanticChanged)
        assertEquals(10_000L, repository.journal(JOURNAL_ID)?.detailedCapturedThroughEpochMillis)
        val overlapping = repository.activeSemanticSegments(JOURNAL_ID, 8_000, 9_000)
        assertEquals("NEWER", overlapping.first().activityType)
        assertEquals(10_000L, overlapping.first().snapshotCapturedAtEpochMillis)
        assertEquals("OLDER", overlapping.last().activityType)
        assertEquals(9_000L, overlapping.last().snapshotCapturedAtEpochMillis)
    }

    @Test
    fun identicalSemanticProjectionDoesNotStoreAnotherSnapshotOrRequestRefresh() = runBlocking {
        val segments = listOf(
            segment(1_000, 2_000, "VISIT", activityType = "STILL"),
            segment(2_000, 3_000, "ACTIVITY", activityType = "WALKING"),
        )
        repository.import(JOURNAL_ID, importInput("first", 10_000, segments = segments))

        val repeatedProjection = repository.import(
            JOURNAL_ID,
            importInput("different-file-same-semantics", 20_000, segments = segments),
        ) as JournalImportResult.Committed

        assertEquals(1, dao.committedSnapshotCount(JOURNAL_ID))
        assertEquals(0, repeatedProjection.semanticSegmentCount)
        assertEquals(false, repeatedProjection.activeSemanticChanged)
        assertEquals(false, repeatedProjection.needsRouteRefresh)
        assertNull(repeatedProjection.changedStartEpochMillis)
        assertNull(repeatedProjection.changedEndEpochMillis)
    }

    @Test
    fun fullyCoveredBackfillDoesNotStoreSemanticSnapshotOrRequestRefresh() = runBlocking {
        val fullSegments = listOf(segment(1_000, 10_000, "ACTIVITY", activityType = "DRIVING"))
        repository.import(
            JOURNAL_ID,
            importInput(
                "current",
                10_000,
                observations = listOf(observation(2_000), observation(10_000)),
                segments = fullSegments,
            ),
        )

        val coveredBackfill = repository.import(
            JOURNAL_ID,
            importInput(
                "covered-backfill",
                20_000,
                observations = listOf(observation(2_000)),
                segments = listOf(segment(2_000, 5_000, "ACTIVITY", activityType = "DRIVING")),
            ),
        ) as JournalImportResult.Committed

        assertEquals(JournalImportResult.ChangeKind.BACKFILL, coveredBackfill.changeKind)
        assertEquals(1, dao.committedSnapshotCount(JOURNAL_ID))
        assertEquals(0, coveredBackfill.semanticSegmentCount)
        assertEquals(false, coveredBackfill.needsRouteRefresh)
        assertNull(coveredBackfill.changedStartEpochMillis)
        assertNull(coveredBackfill.changedEndEpochMillis)
    }

    @Test
    fun largeImportWritesInChunksAndReportsProgress() = runBlocking {
        val observations = (0 until 10_000).map { index ->
            observation(2_000L + index, latitude = 37.0 + index / 1_000_000.0)
        }
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = repository.import(
            JOURNAL_ID,
            importInput("large", 20_000, observations),
        ) { processed, total -> progress += processed to total } as JournalImportResult.Committed

        assertEquals(10_000, result.insertedObservationCount)
        assertEquals(10_000, dao.observationCount(JOURNAL_ID))
        assertEquals(10_000, dao.provenanceCount(result.batchId))
        assertEquals(listOf(0, 4_096, 8_192, 10_000), progress.map { it.first })
        assertEquals(setOf(10_000), progress.map { it.second }.toSet())
    }

    @Test
    fun semanticSegmentsAreChunkedAndIncludedInSaveProgress() = runBlocking {
        val segments = (0 until 2_501).map { index ->
            segment(index.toLong(), index.toLong() + 1, "VISIT")
        }
        val progress = mutableListOf<Pair<Int, Int>>()

        repository.import(JOURNAL_ID, importInput("semantic-large", 20_000, segments = segments)) {
            processed, total -> progress += processed to total
        }

        assertEquals(listOf(0, 1_000, 2_000, 2_501), progress.map { it.first })
        assertEquals(setOf(2_501), progress.map { it.second }.toSet())
    }

    @Test
    fun importPreservesExplicitReminderConsentState() = runBlocking {
        val first = repository.import(
            JOURNAL_ID,
            importInput("recent", 10_000, observations = listOf(observation(9_000), observation(10_000))),
        ) as JournalImportResult.Committed
        repository.setReminderEligible(JOURNAL_ID, eligible = true)
        repository.setReminderEnabled(JOURNAL_ID, enabled = true)
        val armed = requireNotNull(repository.journal(JOURNAL_ID))
        assertEquals(true, armed.reminderEligible)
        assertEquals(true, armed.reminderEnabled)
        assertEquals(10_000L, armed.detailedCapturedThroughEpochMillis)
        assertEquals(10_000L, armed.lastAdvancedAtEpochMillis)

        val duplicate = repository.import(
            JOURNAL_ID,
            importInput("recent", 20_000, observations = listOf(observation(9_000), observation(10_000))),
        )
        assertEquals(JournalImportResult.AlreadyImported(first.batchId), duplicate)

        repository.import(
            JOURNAL_ID,
            importInput("older", 30_000, observations = listOf(observation(1_000), observation(2_000))),
        )
        repository.import(
            JOURNAL_ID,
            importInput("semantic-only", 40_000, segments = listOf(segment(11_000, 12_000, "VISIT"))),
        )
        val unchanged = requireNotNull(repository.journal(JOURNAL_ID))
        assertEquals(true, unchanged.reminderEligible)
        assertEquals(true, unchanged.reminderEnabled)
        assertEquals(10_000L, unchanged.detailedCapturedThroughEpochMillis)
        assertEquals(10_000L, unchanged.lastAdvancedAtEpochMillis)

        repository.import(
            JOURNAL_ID,
            importInput("advanced", 50_000, observations = listOf(observation(10_000), observation(15_000))),
        )
        val advanced = requireNotNull(repository.journal(JOURNAL_ID))
        assertEquals(15_000L, advanced.detailedCapturedThroughEpochMillis)
        assertEquals(50_000L, advanced.lastAdvancedAtEpochMillis)
        assertEquals(true, advanced.reminderEnabled)
    }

    @Test
    fun identityProbeUsesBoundedDeterministicSamplesInsideCommittedDates() = runBlocking {
        val committed = (0 until 100).map { index -> observation(10_000L + index, 37.0 + index / 10_000.0) }
        repository.import(JOURNAL_ID, importInput("base", 20_000, committed))
        val nextExport = listOf(observation(1_000, 1.0)) + committed + listOf(observation(50_000, 2.0))

        assertEquals(32, repository.detailedOverlapCount(JOURNAL_ID, nextExport))
        assertEquals(true, repository.hasLikelySameDetailedIdentity(JOURNAL_ID, nextExport))
        assertEquals(0, repository.detailedOverlapCount(JOURNAL_ID, listOf(observation(50_000, 2.0))))
        assertEquals(false, repository.hasLikelySameDetailedIdentity(JOURNAL_ID, listOf(observation(50_000, 2.0))))
    }

    @Test
    fun partialSemanticSnapshotDoesNotReplaceOlderUniqueCoverage() = runBlocking {
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "old",
                importedAt = 10_000,
                segments = listOf(
                    segment(1_000, 2_000, "VISIT"),
                    segment(2_000, 3_000, "ACTIVITY"),
                ),
            ),
        )
        repository.import(
            JOURNAL_ID,
            importInput(
                hash = "new-partial",
                importedAt = 20_000,
                segments = listOf(segment(2_000, 3_000, "ACTIVITY")),
            ),
        )

        assertEquals(2, dao.committedSnapshotCount(JOURNAL_ID))
        val segments = dao.committedSemanticSegmentsNewestFirst(JOURNAL_ID)
        assertEquals(3, segments.size)
        assertEquals(2_000L, segments.first().startEpochMillis)
        assertEquals(true, segments.any { it.startEpochMillis == 1_000L })
        val journal = dao.journal(JOURNAL_ID)
        assertEquals(1_000L, journal?.semanticStartEpochMillis)
        assertEquals(3_000L, journal?.semanticEndEpochMillis)
        assertNull(journal?.detailedCapturedThroughEpochMillis)
    }

    @Test
    fun invalidObservationRollsBackTheWholeImport() = runBlocking {
        try {
            repository.import(
                JOURNAL_ID,
                importInput(
                    hash = "invalid",
                    importedAt = 10_000,
                    observations = listOf(observation(2_000), observation(3_000, latitude = 95.0)),
                ),
            )
        } catch (_: IllegalArgumentException) {
            // Expected.
        }

        assertEquals(0, dao.observationCount(JOURNAL_ID))
        assertNull(dao.committedBatchByHash(JOURNAL_ID, "invalid"))
    }

    @Test
    fun likelyDifferentImportCannotMutateTheSelectedJournal() = runBlocking {
        val mismatched = importInput(
            hash = "another-person",
            importedAt = 10_000,
            observations = listOf(observation(2_000)),
        ).copy(matchClassification = JournalMatchClassification.LIKELY_DIFFERENT)

        try {
            repository.import(JOURNAL_ID, mismatched)
        } catch (_: IllegalArgumentException) {
            // Expected. The destination flow must create or explicitly approve another Journal.
        }

        assertEquals(0, dao.observationCount(JOURNAL_ID))
        assertNull(dao.committedBatchByHash(JOURNAL_ID, "another-person"))
    }

    @Test
    fun failedFirstImportDoesNotLeaveAnEmptyJournal() = runBlocking {
        val journal = JournalEntity(
            id = "new-journal",
            name = "New Journal",
            isPrimary = false,
            createdAtEpochMillis = 2_000,
        )
        val invalid = importInput(
            hash = "invalid-first",
            importedAt = 10_000,
            observations = listOf(observation(3_000, latitude = 95.0)),
        ).copy(matchClassification = JournalMatchClassification.NEW_JOURNAL)

        try {
            repository.createJournalAndImport(journal, invalid)
        } catch (_: IllegalArgumentException) {
            // Expected. Journal creation and its first import share one transaction.
        }

        assertNull(repository.journal(journal.id))
    }

    private fun importInput(
        hash: String,
        importedAt: Long,
        observations: List<DetailedObservationInput> = emptyList(),
        segments: List<SemanticSegmentInput> = emptyList(),
    ) = JournalImport(
        sourceHash = hash,
        sourceName = "timeline.json",
        sourceSize = 1_024,
        importedAtEpochMillis = importedAt,
        parserVersion = 1,
        matchClassification = JournalMatchClassification.LIKELY_SAME,
        detailedObservations = observations,
        semanticSegments = segments,
    )

    private fun observation(
        instant: Long,
        latitude: Double = 37.5,
        accuracy: Double? = 12.0,
    ) = DetailedObservationInput(
        instantEpochMillis = instant,
        latitude = latitude,
        longitude = 127.0,
        accuracyMeters = accuracy,
    )

    private fun segment(
        start: Long,
        end: Long,
        kind: String,
        activityType: String? = null,
    ) = SemanticSegmentInput(
        startEpochMillis = start,
        endEpochMillis = end,
        kind = kind,
        activityType = activityType,
    )

    private companion object {
        const val JOURNAL_ID = "journal-1"
    }
}
