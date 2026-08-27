package dev.mahlernim.timelinevisualizer.journal

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalDatabaseMigrationTest {
    @After
    fun cleanUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationPreservesVersionOneJournalDataAndAddsEmptyProjectionTables() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).apply {
            VERSION_ONE_SCHEMA.forEach(::execSQL)
            execSQL("INSERT INTO journals VALUES ('journal', 'My Journal', 1, 100, 200, 300, 250, 10, 400, 1, 1)")
            execSQL("INSERT INTO import_batches VALUES ('batch', 'journal', 'hash', 'Timeline.json', 1234, 500, 2, 'LIKELY_SAME', 'COMMITTED', 100, 300, 10, 400, 2, 2, 0, 1, 0)")
            execSQL("INSERT INTO detailed_observations VALUES (1, 'journal', 250, 37.5, 127.0, 'point-key')")
            execSQL("INSERT INTO observation_imports VALUES ('batch', 1, 5.0, 12.0, 3.0, 'gps')")
            execSQL("INSERT INTO semantic_snapshots VALUES ('snapshot', 'batch', 400, 10, 400)")
            execSQL("INSERT INTO semantic_segments VALUES (1, 'snapshot', 0, 10, 400, 'PATH', 'DRIVE', 'place', 'geometry')")
            version = 1
            close()
        }

        val database = Room.databaseBuilder(context, JournalDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                JournalDatabase.MIGRATION_1_2,
                JournalDatabase.MIGRATION_2_3,
                JournalDatabase.MIGRATION_3_4,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migrated = database.openHelper.writableDatabase
            migrated.query("SELECT name, reminderEligible, reminderEnabled, detailedUsableThroughEpochMillis FROM journals WHERE id = 'journal'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("My Journal", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(1, cursor.getInt(2))
                assertEquals(250L, cursor.getLong(3))
            }
            assertEquals(1, migrated.count("import_batches"))
            assertEquals(1, migrated.count("detailed_observations"))
            assertEquals(1, migrated.count("observation_imports"))
            assertEquals(1, migrated.count("semantic_snapshots"))
            assertEquals(1, migrated.count("semantic_segments"))
            assertEquals(0, migrated.count("route_projection_states"))
            assertEquals(0, migrated.count("route_projection_spans"))
            assertEquals(0, migrated.count("route_projection_chunks"))
            val state = JournalRepository(database).ensureRouteProjectionState("journal")
            assertEquals(1L, state.sourceRevision)
            assertEquals("DIRTY", state.buildStatus)
            assertNotNull(database.journalDao().journal("journal"))
        } finally {
            database.close()
        }
    }

    @Test
    fun versionTwoLifetimeProjectionKeepsItsRowsAndReceivesLifetimeBounds() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).apply {
            VERSION_ONE_SCHEMA.forEach(::execSQL)
            VERSION_TWO_PROJECTION_SCHEMA.forEach(::execSQL)
            execSQL("INSERT INTO journals VALUES ('journal', 'My Journal', 1, 100, 200, 300, 250, 10, 400, 1, 1)")
            execSQL("INSERT INTO route_projection_states VALUES ('journal', 4, 4, 1, 'READY', NULL, NULL, 500, 1, 2, 2, 2, 0)")
            execSQL("INSERT INTO route_projection_spans VALUES (1, 'journal', 0, 10, 20, 'DETAILED', NULL, 2)")
            execSQL("INSERT INTO route_projection_chunks VALUES (1, 0, 1, 2, X'0001')")
            version = 2
            close()
        }

        val database = Room.databaseBuilder(context, JournalDatabase::class.java, DATABASE_NAME)
            .addMigrations(
                JournalDatabase.MIGRATION_1_2,
                JournalDatabase.MIGRATION_2_3,
                JournalDatabase.MIGRATION_3_4,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val migrated = database.openHelper.writableDatabase
            migrated.query(
                "SELECT projectionStartEpochMillis, projectionEndExclusiveEpochMillis FROM route_projection_states WHERE journalId = 'journal'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(Long.MIN_VALUE, cursor.getLong(0))
                assertEquals(Long.MAX_VALUE, cursor.getLong(1))
            }
            assertEquals(1, migrated.count("route_projection_spans"))
            assertEquals(1, migrated.count("route_projection_chunks"))
            migrated.query(
                "SELECT startEpochMillis, endExclusiveEpochMillis FROM route_projection_chunks WHERE spanId = 1",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(true, cursor.isNull(0))
                assertEquals(true, cursor.isNull(1))
            }
        } finally {
            database.close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "journal-migration-test.db"
        val VERSION_ONE_SCHEMA = listOf(
            "CREATE TABLE journals (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isPrimary INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, lastAdvancedAtEpochMillis INTEGER, detailedCapturedThroughEpochMillis INTEGER, detailedUsableThroughEpochMillis INTEGER, semanticStartEpochMillis INTEGER, semanticEndEpochMillis INTEGER, reminderEligible INTEGER NOT NULL, reminderEnabled INTEGER NOT NULL)",
            "CREATE TABLE import_batches (id TEXT NOT NULL PRIMARY KEY, journalId TEXT NOT NULL, sourceHash TEXT NOT NULL, sourceName TEXT, sourceSize INTEGER, importedAtEpochMillis INTEGER NOT NULL, parserVersion INTEGER NOT NULL, matchClassification TEXT NOT NULL, status TEXT NOT NULL, detailedStartEpochMillis INTEGER, detailedEndEpochMillis INTEGER, semanticStartEpochMillis INTEGER, semanticEndEpochMillis INTEGER, parsedObservationCount INTEGER NOT NULL, insertedObservationCount INTEGER NOT NULL, duplicateObservationCount INTEGER NOT NULL, rejectedObservationCount INTEGER NOT NULL, conflictObservationCount INTEGER NOT NULL, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_import_batches_journalId ON import_batches (journalId)",
            "CREATE UNIQUE INDEX index_import_batches_journalId_sourceHash ON import_batches (journalId, sourceHash)",
            "CREATE TABLE detailed_observations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, journalId TEXT NOT NULL, instantEpochMillis INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, observationKey TEXT NOT NULL, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_detailed_observations_journalId ON detailed_observations (journalId)",
            "CREATE UNIQUE INDEX index_detailed_observations_journalId_observationKey ON detailed_observations (journalId, observationKey)",
            "CREATE INDEX index_detailed_observations_journalId_instantEpochMillis ON detailed_observations (journalId, instantEpochMillis)",
            "CREATE TABLE observation_imports (importBatchId TEXT NOT NULL, observationId INTEGER NOT NULL, accuracyMeters REAL, altitudeMeters REAL, speedMetersPerSecond REAL, provider TEXT, PRIMARY KEY(importBatchId, observationId), FOREIGN KEY(importBatchId) REFERENCES import_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(observationId) REFERENCES detailed_observations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_observation_imports_observationId ON observation_imports (observationId)",
            "CREATE TABLE semantic_snapshots (id TEXT NOT NULL PRIMARY KEY, importBatchId TEXT NOT NULL, capturedAtEpochMillis INTEGER NOT NULL, startEpochMillis INTEGER, endEpochMillis INTEGER, FOREIGN KEY(importBatchId) REFERENCES import_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX index_semantic_snapshots_importBatchId ON semantic_snapshots (importBatchId)",
            "CREATE TABLE semantic_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, snapshotId TEXT NOT NULL, sourceOrdinal INTEGER NOT NULL, startEpochMillis INTEGER NOT NULL, endEpochMillis INTEGER NOT NULL, kind TEXT NOT NULL, activityType TEXT, placeId TEXT, geometryJson TEXT, FOREIGN KEY(snapshotId) REFERENCES semantic_snapshots(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_semantic_segments_snapshotId ON semantic_segments (snapshotId)",
            "CREATE UNIQUE INDEX index_semantic_segments_snapshotId_sourceOrdinal ON semantic_segments (snapshotId, sourceOrdinal)",
            "CREATE INDEX index_semantic_segments_startEpochMillis_endEpochMillis ON semantic_segments (startEpochMillis, endEpochMillis)",
        )
        val VERSION_TWO_PROJECTION_SCHEMA = listOf(
            "CREATE TABLE route_projection_states (journalId TEXT NOT NULL PRIMARY KEY, sourceRevision INTEGER NOT NULL, builtRevision INTEGER NOT NULL, algorithmVersion INTEGER NOT NULL, buildStatus TEXT NOT NULL, dirtyStartEpochMillis INTEGER, dirtyEndEpochMillis INTEGER, updatedAtEpochMillis INTEGER, spanCount INTEGER NOT NULL, pointCount INTEGER NOT NULL, detailedInputCount INTEGER NOT NULL, detailedUsableCount INTEGER NOT NULL, semanticUsableCount INTEGER NOT NULL, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE TABLE route_projection_spans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, journalId TEXT NOT NULL, ordinal INTEGER NOT NULL, startEpochMillis INTEGER NOT NULL, endEpochMillis INTEGER NOT NULL, source TEXT NOT NULL, transitionReason TEXT, pointCount INTEGER NOT NULL, FOREIGN KEY(journalId) REFERENCES journals(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE UNIQUE INDEX index_route_projection_spans_journalId_ordinal ON route_projection_spans (journalId, ordinal)",
            "CREATE INDEX index_route_projection_spans_journalId_startEpochMillis_endEpochMillis ON route_projection_spans (journalId, startEpochMillis, endEpochMillis)",
            "CREATE TABLE route_projection_chunks (spanId INTEGER NOT NULL, chunkOrdinal INTEGER NOT NULL, formatVersion INTEGER NOT NULL, pointCount INTEGER NOT NULL, pointData BLOB NOT NULL, PRIMARY KEY(spanId, chunkOrdinal), FOREIGN KEY(spanId) REFERENCES route_projection_spans(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_route_projection_chunks_spanId ON route_projection_chunks (spanId)",
        )
    }
}
