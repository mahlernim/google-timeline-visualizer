package dev.mahlernim.timelinevisualizer.journal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface JournalDao {
    @Insert
    suspend fun insertJournal(journal: JournalEntity)

    @Query("SELECT * FROM journals WHERE id = :journalId")
    suspend fun journal(journalId: String): JournalEntity?

    @Query("SELECT * FROM journals WHERE isPrimary = 1 ORDER BY createdAtEpochMillis ASC LIMIT 1")
    suspend fun primaryJournal(): JournalEntity?

    @Update
    suspend fun updateJournal(journal: JournalEntity)

    @Insert
    suspend fun insertBatch(batch: ImportBatchEntity)

    @Update
    suspend fun updateBatch(batch: ImportBatchEntity)

    @Query("SELECT * FROM import_batches WHERE journalId = :journalId AND sourceHash = :sourceHash AND status = 'COMMITTED' LIMIT 1")
    suspend fun committedBatchByHash(journalId: String, sourceHash: String): ImportBatchEntity?

    @Query("SELECT * FROM import_batches WHERE id = :batchId")
    suspend fun batch(batchId: String): ImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservations(observations: List<DetailedObservationEntity>): List<Long>

    @Query("SELECT observationKey, id FROM detailed_observations WHERE journalId = :journalId AND observationKey IN (:observationKeys)")
    suspend fun observationIds(
        journalId: String,
        observationKeys: List<String>,
    ): List<ObservationKeyId>

    @Query(
        """
        SELECT observation_imports.observationId AS observationId,
               MIN(observation_imports.accuracyMeters) AS accuracyMeters
        FROM observation_imports
        INNER JOIN import_batches
            ON import_batches.id = observation_imports.importBatchId
        WHERE import_batches.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND observation_imports.observationId IN (:observationIds)
        GROUP BY observation_imports.observationId
        """,
    )
    suspend fun committedBestAccuracy(
        journalId: String,
        observationIds: List<Long>,
    ): List<CommittedObservationAccuracy>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObservationImports(provenance: List<ObservationImportEntity>)

    @Insert
    suspend fun insertSemanticSnapshot(snapshot: SemanticSnapshotEntity)

    @Insert
    suspend fun insertSemanticSegments(segments: List<SemanticSegmentEntity>)

    @Query("SELECT COUNT(*) FROM detailed_observations WHERE journalId = :journalId")
    suspend fun observationCount(journalId: String): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM semantic_segments
        WHERE snapshotId = (
            SELECT semantic_snapshots.id
            FROM semantic_snapshots
            INNER JOIN import_batches
                ON import_batches.id = semantic_snapshots.importBatchId
            WHERE import_batches.journalId = :journalId
              AND import_batches.status = 'COMMITTED'
            ORDER BY semantic_snapshots.capturedAtEpochMillis DESC,
                     semantic_snapshots.id DESC
            LIMIT 1
        )
        """,
    )
    suspend fun latestPreferredSemanticSegmentCount(journalId: String): Int

    @Query("SELECT MAX(importedAtEpochMillis) FROM import_batches WHERE journalId = :journalId AND status = 'COMMITTED'")
    suspend fun latestCommittedImportAt(journalId: String): Long?

    @Query("SELECT COUNT(*) FROM import_batches WHERE journalId = :journalId AND status = 'COMMITTED'")
    suspend fun committedImportCount(journalId: String): Int

    @Query("UPDATE journals SET reminderEligible = :eligible WHERE id = :journalId")
    suspend fun setReminderEligible(journalId: String, eligible: Boolean)

    @Query("UPDATE journals SET reminderEnabled = :enabled WHERE id = :journalId")
    suspend fun setReminderEnabled(journalId: String, enabled: Boolean)

    @Query("UPDATE journals SET detailedUsableThroughEpochMillis = :usableThroughEpochMillis WHERE id = :journalId")
    suspend fun setDetailedUsableThrough(journalId: String, usableThroughEpochMillis: Long?)

    @Query("SELECT COUNT(*) FROM observation_imports WHERE importBatchId = :batchId")
    suspend fun provenanceCount(batchId: String): Int

    @Query("SELECT COUNT(*) FROM semantic_snapshots INNER JOIN import_batches ON import_batches.id = semantic_snapshots.importBatchId WHERE import_batches.journalId = :journalId AND import_batches.status = 'COMMITTED'")
    suspend fun committedSnapshotCount(journalId: String): Int

    @Query("SELECT MAX(semantic_snapshots.capturedAtEpochMillis) FROM semantic_snapshots INNER JOIN import_batches ON import_batches.id = semantic_snapshots.importBatchId WHERE import_batches.journalId = :journalId AND import_batches.status = 'COMMITTED'")
    suspend fun latestCommittedSemanticCapture(journalId: String): Long?

    @Query("SELECT semantic_segments.* FROM semantic_segments INNER JOIN semantic_snapshots ON semantic_snapshots.id = semantic_segments.snapshotId INNER JOIN import_batches ON import_batches.id = semantic_snapshots.importBatchId WHERE import_batches.journalId = :journalId AND import_batches.status = 'COMMITTED' ORDER BY semantic_snapshots.capturedAtEpochMillis DESC, semantic_segments.sourceOrdinal ASC")
    suspend fun committedSemanticSegmentsNewestFirst(journalId: String): List<SemanticSegmentEntity>

    @Query(
        """
        SELECT semantic_segments.*
        FROM semantic_segments
        WHERE semantic_segments.snapshotId = (
            SELECT semantic_snapshots.id
            FROM semantic_snapshots
            INNER JOIN import_batches
                ON import_batches.id = semantic_snapshots.importBatchId
            WHERE import_batches.journalId = :journalId
              AND import_batches.status = 'COMMITTED'
            ORDER BY semantic_snapshots.capturedAtEpochMillis DESC,
                     semantic_snapshots.id DESC
            LIMIT 1
        )
        ORDER BY semantic_segments.sourceOrdinal ASC
        """,
    )
    suspend fun latestPreferredSemanticSegments(journalId: String): List<SemanticSegmentEntity>

    @Query(
        """
        SELECT detailed_observations.instantEpochMillis,
               detailed_observations.latitude,
               detailed_observations.longitude,
               MIN(observation_imports.accuracyMeters) AS accuracyMeters
        FROM detailed_observations
        INNER JOIN observation_imports
            ON observation_imports.observationId = detailed_observations.id
        INNER JOIN import_batches
            ON import_batches.id = observation_imports.importBatchId
        WHERE detailed_observations.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND detailed_observations.instantEpochMillis >= :startEpochMillis
          AND detailed_observations.instantEpochMillis < :endExclusiveEpochMillis
          AND observation_imports.accuracyMeters IS NOT NULL
        GROUP BY detailed_observations.id
        ORDER BY detailed_observations.instantEpochMillis ASC,
                 detailed_observations.id ASC
        """,
    )
    suspend fun activeDetailedObservations(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveDetailedObservation>

    @Query(
        """
        SELECT semantic_segments.*,
               import_batches.parserVersion AS parserVersion,
               semantic_snapshots.capturedAtEpochMillis AS snapshotCapturedAtEpochMillis
        FROM semantic_segments
        INNER JOIN semantic_snapshots
            ON semantic_snapshots.id = semantic_segments.snapshotId
        INNER JOIN import_batches
            ON import_batches.id = semantic_snapshots.importBatchId
        WHERE import_batches.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND semantic_segments.endEpochMillis >= :startEpochMillis
          AND semantic_segments.startEpochMillis < :endExclusiveEpochMillis
        ORDER BY semantic_snapshots.capturedAtEpochMillis DESC,
                 semantic_snapshots.id DESC,
                 semantic_segments.sourceOrdinal ASC
        """,
    )
    suspend fun activeSemanticSegmentsNewestFirst(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveSemanticSegment>

    @Query(
        """
        SELECT semantic_segments.*,
               import_batches.parserVersion AS parserVersion,
               semantic_snapshots.capturedAtEpochMillis AS snapshotCapturedAtEpochMillis
        FROM semantic_segments
        INNER JOIN semantic_snapshots
            ON semantic_snapshots.id = semantic_segments.snapshotId
        INNER JOIN import_batches
            ON import_batches.id = semantic_snapshots.importBatchId
        WHERE import_batches.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND semantic_snapshots.id IN (:snapshotIds)
        ORDER BY semantic_snapshots.capturedAtEpochMillis DESC,
                 semantic_snapshots.id DESC,
                 semantic_segments.sourceOrdinal ASC
        """,
    )
    suspend fun activeSemanticSegmentsForSnapshotsNewestFirst(
        journalId: String,
        snapshotIds: List<String>,
    ): List<ActiveSemanticSegment>

    @Query(
        """
        SELECT MIN(detailed_observations.instantEpochMillis) AS startEpochMillis,
               MAX(detailed_observations.instantEpochMillis) AS endEpochMillis
        FROM detailed_observations
        INNER JOIN observation_imports
            ON observation_imports.observationId = detailed_observations.id
        INNER JOIN import_batches
            ON import_batches.id = observation_imports.importBatchId
        WHERE detailed_observations.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
        """,
    )
    suspend fun committedDetailedBounds(journalId: String): CommittedDetailedBounds

    @Query(
        """
        SELECT detailed_observations.instantEpochMillis
        FROM detailed_observations
        WHERE detailed_observations.journalId = :journalId
          AND (
              SELECT MIN(observation_imports.accuracyMeters)
              FROM observation_imports
              INNER JOIN import_batches
                  ON import_batches.id = observation_imports.importBatchId
              WHERE observation_imports.observationId = detailed_observations.id
                AND import_batches.status = 'COMMITTED'
                AND observation_imports.accuracyMeters IS NOT NULL
          ) <= :maximumAccuracyMeters
        ORDER BY detailed_observations.instantEpochMillis DESC,
                 detailed_observations.id DESC
        LIMIT 1
        """,
    )
    suspend fun latestUsableDetailedEpochMillis(
        journalId: String,
        maximumAccuracyMeters: Double,
    ): Long?

    @Query(
        """
        SELECT COUNT(DISTINCT detailed_observations.observationKey)
        FROM detailed_observations
        INNER JOIN observation_imports
            ON observation_imports.observationId = detailed_observations.id
        INNER JOIN import_batches
            ON import_batches.id = observation_imports.importBatchId
        WHERE detailed_observations.journalId = :journalId
          AND import_batches.status = 'COMMITTED'
          AND detailed_observations.observationKey IN (:observationKeys)
        """,
    )
    suspend fun committedObservationKeyCount(
        journalId: String,
        observationKeys: List<String>,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRouteProjectionState(state: RouteProjectionStateEntity): Long

    @Query("SELECT * FROM route_projection_states WHERE journalId = :journalId")
    suspend fun routeProjectionState(journalId: String): RouteProjectionStateEntity?

    @Update
    suspend fun updateRouteProjectionState(state: RouteProjectionStateEntity)

    @Query(
        """
        UPDATE route_projection_states
        SET sourceRevision = sourceRevision + 1,
            buildStatus = 'DIRTY',
            dirtyStartEpochMillis = CASE
                WHEN dirtyStartEpochMillis IS NULL THEN :changedStartEpochMillis
                WHEN :changedStartEpochMillis IS NULL THEN NULL
                ELSE MIN(dirtyStartEpochMillis, :changedStartEpochMillis)
            END,
            dirtyEndEpochMillis = CASE
                WHEN dirtyEndEpochMillis IS NULL THEN :changedEndEpochMillis
                WHEN :changedEndEpochMillis IS NULL THEN NULL
                ELSE MAX(dirtyEndEpochMillis, :changedEndEpochMillis)
            END
        WHERE journalId = :journalId
        """,
    )
    suspend fun markRouteProjectionDirty(
        journalId: String,
        changedStartEpochMillis: Long?,
        changedEndEpochMillis: Long?,
    )

    @Query("SELECT * FROM route_projection_spans WHERE journalId = :journalId ORDER BY ordinal ASC")
    suspend fun routeProjectionSpans(journalId: String): List<RouteProjectionSpanEntity>

    @Query(
        """
        SELECT * FROM route_projection_spans
        WHERE journalId = :journalId
          AND endEpochMillis >= :startEpochMillis
          AND startEpochMillis < :endExclusiveEpochMillis
        ORDER BY ordinal ASC
        """,
    )
    suspend fun routeProjectionSpansInRange(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<RouteProjectionSpanEntity>

    @Query("SELECT * FROM route_projection_chunks WHERE spanId IN (:spanIds) ORDER BY spanId ASC, chunkOrdinal ASC")
    suspend fun routeProjectionChunks(spanIds: List<Long>): List<RouteProjectionChunkEntity>

    @Query(
        """
        SELECT * FROM route_projection_chunks
        WHERE spanId IN (:spanIds)
          AND (
              startEpochMillis IS NULL OR endExclusiveEpochMillis IS NULL OR
              (endExclusiveEpochMillis > :startEpochMillis AND startEpochMillis < :endExclusiveEpochMillis)
          )
        ORDER BY spanId ASC, chunkOrdinal ASC
        """,
    )
    suspend fun routeProjectionChunksInRange(
        spanIds: List<Long>,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<RouteProjectionChunkEntity>

    @Insert
    suspend fun insertRouteProjectionSpan(span: RouteProjectionSpanEntity): Long

    @Insert
    suspend fun insertRouteProjectionChunks(chunks: List<RouteProjectionChunkEntity>)

    @Query("DELETE FROM route_projection_spans WHERE journalId = :journalId")
    suspend fun deleteRouteProjectionSpans(journalId: String)
}
