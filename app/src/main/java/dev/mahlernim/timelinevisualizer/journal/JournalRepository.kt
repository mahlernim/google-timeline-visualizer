package dev.mahlernim.timelinevisualizer.journal

import androidx.room.withTransaction
import dev.mahlernim.timelinevisualizer.data.RawSignalProcessor
import java.util.UUID

data class DetailedObservationInput(
    val instantEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val provider: String? = null,
)

data class SemanticSegmentInput(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val kind: String,
    val activityType: String? = null,
    val placeId: String? = null,
    val geometryJson: String? = null,
)

enum class JournalMatchClassification {
    LIKELY_SAME,
    UNCERTAIN,
    LIKELY_DIFFERENT,
    NEW_JOURNAL,
    EXPLICITLY_APPROVED,
}

data class JournalImport(
    val sourceHash: String,
    val sourceName: String?,
    val sourceSize: Long?,
    val importedAtEpochMillis: Long,
    val parserVersion: Int,
    val matchClassification: JournalMatchClassification,
    val detailedObservations: List<DetailedObservationInput>,
    val semanticSegments: List<SemanticSegmentInput>,
    val rejectedObservationCount: Int = 0,
    val conflictObservationCount: Int = 0,
)

sealed interface JournalImportResult {
    enum class ChangeKind {
        INITIAL,
        ADVANCED,
        BACKFILL,
        OVERLAP,
    }

    data class Committed(
        val batchId: String,
        val insertedObservationCount: Int,
        val duplicateObservationCount: Int,
        val semanticSegmentCount: Int,
        val changeKind: ChangeKind,
        val changedStartEpochMillis: Long?,
        val changedEndEpochMillis: Long?,
        val activeSemanticChanged: Boolean,
        val needsRouteRefresh: Boolean,
    ) : JournalImportResult

    data class AlreadyImported(val batchId: String) : JournalImportResult
}

data class JournalStatusSnapshot(
    val journal: JournalEntity,
    val preservedObservationCount: Int,
    val detailedStartEpochMillis: Long?,
    val detailedEndEpochMillis: Long?,
    val semanticEntryCount: Int,
    val lastSuccessfulImportAtEpochMillis: Long?,
)

class JournalRepository(
    internal val database: JournalDatabase,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.journalDao()

    suspend fun createJournal(journal: JournalEntity) = database.withTransaction {
        dao.insertJournal(journal)
    }

    suspend fun createJournalAndImport(
        journal: JournalEntity,
        input: JournalImport,
        onProgress: (processedRecordCount: Int, totalRecordCount: Int) -> Unit = { _, _ -> },
    ): JournalImportResult = database.withTransaction {
        dao.insertJournal(journal)
        import(journal.id, input, onProgress)
    }

    suspend fun journal(journalId: String): JournalEntity? = dao.journal(journalId)

    suspend fun primaryJournal(): JournalEntity? = dao.primaryJournal()

    suspend fun status(journalId: String): JournalStatusSnapshot? {
        val journal = dao.journal(journalId) ?: return null
        val detailedBounds = dao.committedDetailedBounds(journalId)
        return JournalStatusSnapshot(
            journal = journal,
            preservedObservationCount = dao.observationCount(journalId),
            detailedStartEpochMillis = detailedBounds.startEpochMillis,
            detailedEndEpochMillis = detailedBounds.endEpochMillis,
            semanticEntryCount = dao.latestPreferredSemanticSegmentCount(journalId),
            lastSuccessfulImportAtEpochMillis = dao.latestCommittedImportAt(journalId),
        )
    }

    suspend fun setReminderEligible(journalId: String, eligible: Boolean) =
        dao.setReminderEligible(journalId, eligible)

    suspend fun setReminderEnabled(journalId: String, enabled: Boolean) =
        dao.setReminderEnabled(journalId, enabled)

    suspend fun setDetailedUsableThrough(journalId: String, usableThroughEpochMillis: Long?) =
        dao.setDetailedUsableThrough(journalId, usableThroughEpochMillis)

    /** Lazily seeds projection metadata for Journals created before the projection schema existed. */
    suspend fun ensureRouteProjectionState(journalId: String): RouteProjectionStateEntity =
        database.withTransaction {
            dao.routeProjectionState(journalId)?.let { return@withTransaction it }
            requireNotNull(dao.journal(journalId)) { "Journal does not exist" }
            val hasCommittedSource = dao.committedImportCount(journalId) > 0
            val initial = RouteProjectionStateEntity(
                journalId = journalId,
                sourceRevision = if (hasCommittedSource) 1 else 0,
                builtRevision = 0,
                algorithmVersion = 0,
                buildStatus = if (hasCommittedSource) "DIRTY" else "EMPTY",
            )
            dao.insertRouteProjectionState(initial)
            requireNotNull(dao.routeProjectionState(journalId))
        }

    suspend fun committedImport(journalId: String, sourceHash: String): ImportBatchEntity? {
        require(sourceHash.isNotBlank()) { "sourceHash must not be blank" }
        return dao.committedBatchByHash(journalId, sourceHash)
    }

    /**
     * Returns a bounded count of deterministic detail samples that exactly match committed detail.
     *
     * Callers use zero versus nonzero as identity evidence. This deliberately avoids scanning every
     * point in a large rolling export.
     */
    suspend fun detailedOverlapCount(
        journalId: String,
        candidates: List<DetailedObservationInput>,
    ): Int {
        if (candidates.isEmpty()) return 0
        val committedBounds = dao.committedDetailedBounds(journalId)
        val committedStart = committedBounds.startEpochMillis ?: return 0
        val committedEnd = committedBounds.endEpochMillis ?: return 0
        val overlapping = candidates.asSequence()
            .filter { it.instantEpochMillis in committedStart..committedEnd }
            .toList()
        if (overlapping.isEmpty()) return 0
        val samples = deterministicSamples(overlapping, IDENTITY_SAMPLE_SIZE)
            .map(::observationKey)
            .distinct()
        return dao.committedObservationKeyCount(journalId, samples)
    }

    /** True when a bounded, evenly distributed probe provides useful same-Journal evidence. */
    suspend fun hasLikelySameDetailedIdentity(
        journalId: String,
        candidates: List<DetailedObservationInput>,
    ): Boolean {
        if (candidates.isEmpty()) return false
        val sampleCount = minOf(candidates.size, IDENTITY_SAMPLE_SIZE)
        val matches = detailedOverlapCount(journalId, candidates)
        val requiredMatches = minOf(3, (sampleCount + 3) / 4)
        return matches >= requiredMatches
    }

    suspend fun activeDetailedObservations(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveDetailedObservation> {
        require(endExclusiveEpochMillis > startEpochMillis) { "The route range must not be empty" }
        return dao.activeDetailedObservations(journalId, startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun activeSemanticSegments(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveSemanticSegment> {
        require(endExclusiveEpochMillis > startEpochMillis) { "The route range must not be empty" }
        return dao.activeSemanticSegmentsNewestFirst(journalId, startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun activeSemanticActivitySegments(
        journalId: String,
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
    ): List<ActiveSemanticSegment> {
        require(endExclusiveEpochMillis > startEpochMillis) { "The route range must not be empty" }
        return dao.activeSemanticActivitySegmentsNewestFirst(journalId, startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun activeSemanticSegmentsForSnapshots(
        journalId: String,
        snapshotIds: Collection<String>,
    ): List<ActiveSemanticSegment> = snapshotIds.chunked(SQLITE_BIND_CHUNK_SIZE).flatMap { chunk ->
        dao.activeSemanticSegmentsForSnapshotsNewestFirst(journalId, chunk)
    }

    suspend fun import(
        journalId: String,
        input: JournalImport,
        onProgress: (processedRecordCount: Int, totalRecordCount: Int) -> Unit = { _, _ -> },
    ): JournalImportResult =
        database.withTransaction {
            require(input.sourceHash.isNotBlank()) { "sourceHash must not be blank" }
            val journal = requireNotNull(dao.journal(journalId)) { "Journal does not exist" }
            dao.committedBatchByHash(journalId, input.sourceHash)?.let {
                return@withTransaction JournalImportResult.AlreadyImported(it.id)
            }
            require(
                input.matchClassification in setOf(
                    JournalMatchClassification.LIKELY_SAME,
                    JournalMatchClassification.NEW_JOURNAL,
                    JournalMatchClassification.EXPLICITLY_APPROVED,
                ),
            ) { "This import requires an explicit Journal destination decision" }

            val batchId = idFactory()
            val detailedStart = input.detailedObservations.minOfOrNull { it.instantEpochMillis }
            val detailedEnd = input.detailedObservations.maxOfOrNull { it.instantEpochMillis }
            val semanticStart = input.semanticSegments.minOfOrNull { it.startEpochMillis }
            val semanticEnd = input.semanticSegments.maxOfOrNull { it.endEpochMillis }
            val previousDetailedEnd = journal.detailedCapturedThroughEpochMillis
            val changeKind = when {
                previousDetailedEnd == null && journal.semanticEndEpochMillis == null ->
                    JournalImportResult.ChangeKind.INITIAL
                detailedEnd != null && previousDetailedEnd != null && detailedEnd < previousDetailedEnd ->
                    JournalImportResult.ChangeKind.BACKFILL
                detailedEnd != null && (previousDetailedEnd == null || detailedEnd > previousDetailedEnd) ->
                    JournalImportResult.ChangeKind.ADVANCED
                else -> JournalImportResult.ChangeKind.OVERLAP
            }
            val existingSemanticRows = if (
                changeKind == JournalImportResult.ChangeKind.BACKFILL &&
                semanticStart != null && semanticEnd != null
            ) {
                dao.activeSemanticSegmentsNewestFirst(journalId, semanticStart, incrementSafely(semanticEnd))
            } else {
                emptyList()
            }
            val preferredSemanticRows = if (
                input.semanticSegments.isNotEmpty() && changeKind != JournalImportResult.ChangeKind.BACKFILL
            ) {
                dao.latestPreferredSemanticSegments(journalId)
            } else {
                emptyList()
            }
            val semanticChangeBounds = when {
                input.semanticSegments.isEmpty() -> null
                changeKind != JournalImportResult.ChangeKind.BACKFILL && preferredSemanticRows.isEmpty() ->
                    semanticStart!! to semanticEnd!!
                changeKind != JournalImportResult.ChangeKind.BACKFILL ->
                    semanticDifferenceBounds(input.semanticSegments, preferredSemanticRows)
                else -> uncoveredSemanticBounds(input.semanticSegments, existingSemanticRows)
            }
            val shouldStoreSemanticSnapshot = input.semanticSegments.isNotEmpty() && semanticChangeBounds != null
            val staging = ImportBatchEntity(
                id = batchId,
                journalId = journalId,
                sourceHash = input.sourceHash,
                sourceName = input.sourceName,
                sourceSize = input.sourceSize,
                importedAtEpochMillis = input.importedAtEpochMillis,
                parserVersion = input.parserVersion,
                matchClassification = input.matchClassification.name,
                status = "STAGING",
                detailedStartEpochMillis = detailedStart,
                detailedEndEpochMillis = detailedEnd,
                semanticStartEpochMillis = semanticStart,
                semanticEndEpochMillis = semanticEnd,
                parsedObservationCount = input.detailedObservations.size,
                rejectedObservationCount = input.rejectedObservationCount,
                conflictObservationCount = input.conflictObservationCount,
            )
            dao.insertBatch(staging)

            input.detailedObservations.forEach(::validate)
            var insertedCount = 0
            var insertedStart: Long? = null
            var insertedEnd: Long? = null
            var improvedAccuracyStart: Long? = null
            var improvedAccuracyEnd: Long? = null
            var processedCount = 0
            val totalRecordCount = input.detailedObservations.size + input.semanticSegments.size
            onProgress(0, totalRecordCount)
            for (chunkStart in input.detailedObservations.indices step OBSERVATION_INSERT_CHUNK_SIZE) {
                val chunkEnd = minOf(chunkStart + OBSERVATION_INSERT_CHUNK_SIZE, input.detailedObservations.size)
                val candidates = input.detailedObservations.subList(chunkStart, chunkEnd)
                val entities = candidates.map { candidate ->
                    DetailedObservationEntity(
                        journalId = journalId,
                        instantEpochMillis = candidate.instantEpochMillis,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude,
                        observationKey = observationKey(candidate),
                    )
                }
                val insertedIds = dao.insertObservations(entities)
                check(insertedIds.size == candidates.size) { "Room returned an unexpected insert result count" }
                val duplicateKeys = insertedIds.mapIndexedNotNull { index, insertedId ->
                    if (insertedId == -1L) entities[index].observationKey else null
                }.distinct()
                val duplicateIds = duplicateKeys
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { keys -> dao.observationIds(journalId, keys) }
                    .associate { it.observationKey to it.id }
                val bestCommittedAccuracy = duplicateIds.values
                    .distinct()
                    .chunked(SQLITE_BIND_CHUNK_SIZE)
                    .flatMap { ids -> dao.committedBestAccuracy(journalId, ids) }
                    .associate { it.observationId to it.accuracyMeters }
                val provenance = insertedIds.mapIndexedNotNull { index, insertedId ->
                    val observationId = if (insertedId == -1L) {
                        requireNotNull(duplicateIds[entities[index].observationKey]) {
                            "An ignored observation could not be resolved"
                        }
                    } else {
                        insertedCount += 1
                        insertedStart = minOfNullable(insertedStart, candidates[index].instantEpochMillis)
                        insertedEnd = maxOfNullable(insertedEnd, candidates[index].instantEpochMillis)
                        insertedId
                    }
                    val candidate = candidates[index]
                    if (insertedId == -1L && existingMetadataDominates(candidate, bestCommittedAccuracy[observationId])) {
                        return@mapIndexedNotNull null
                    }
                    if (
                        insertedId == -1L && candidate.accuracyMeters != null &&
                        (bestCommittedAccuracy[observationId] == null ||
                            candidate.accuracyMeters < requireNotNull(bestCommittedAccuracy[observationId]))
                    ) {
                        improvedAccuracyStart = minOfNullable(improvedAccuracyStart, candidate.instantEpochMillis)
                        improvedAccuracyEnd = maxOfNullable(improvedAccuracyEnd, candidate.instantEpochMillis)
                    }
                    ObservationImportEntity(
                        importBatchId = batchId,
                        observationId = observationId,
                        accuracyMeters = candidate.accuracyMeters,
                        altitudeMeters = candidate.altitudeMeters,
                        speedMetersPerSecond = candidate.speedMetersPerSecond,
                        provider = candidate.provider,
                    )
                }
                if (provenance.isNotEmpty()) dao.insertObservationImports(provenance)
                processedCount += candidates.size
                onProgress(processedCount, totalRecordCount)
            }

            if (shouldStoreSemanticSnapshot) {
                val snapshotId = idFactory()
                val sourceCapture = maxOfNullable(detailedEnd, semanticEnd) ?: input.importedAtEpochMillis
                val latestCapture = dao.latestCommittedSemanticCapture(journalId)
                val snapshotCapture = when {
                    latestCapture == null -> sourceCapture
                    changeKind == JournalImportResult.ChangeKind.BACKFILL ->
                        minOf(sourceCapture, decrementSafely(latestCapture))
                    else -> maxOf(sourceCapture, incrementSafely(latestCapture))
                }
                dao.insertSemanticSnapshot(
                    SemanticSnapshotEntity(
                        id = snapshotId,
                        importBatchId = batchId,
                        // Backfills are ranked below committed coverage even when imported later.
                        capturedAtEpochMillis = snapshotCapture,
                        startEpochMillis = semanticStart,
                        endEpochMillis = semanticEnd,
                    ),
                )
                for (chunkStart in input.semanticSegments.indices step SEMANTIC_INSERT_CHUNK_SIZE) {
                    val chunkEnd = minOf(chunkStart + SEMANTIC_INSERT_CHUNK_SIZE, input.semanticSegments.size)
                    val segments = input.semanticSegments.subList(chunkStart, chunkEnd).mapIndexed { offset, segment ->
                        require(segment.endEpochMillis >= segment.startEpochMillis) {
                            "Semantic segment ends before it starts"
                        }
                        SemanticSegmentEntity(
                            snapshotId = snapshotId,
                            sourceOrdinal = chunkStart + offset,
                            startEpochMillis = segment.startEpochMillis,
                            endEpochMillis = segment.endEpochMillis,
                            kind = segment.kind,
                            activityType = segment.activityType,
                            placeId = segment.placeId,
                            geometryJson = segment.geometryJson,
                        )
                    }
                    dao.insertSemanticSegments(segments)
                    processedCount += segments.size
                    onProgress(processedCount, totalRecordCount)
                }
            } else if (input.semanticSegments.isNotEmpty()) {
                processedCount += input.semanticSegments.size
                onProgress(processedCount, totalRecordCount)
            }

            val duplicateCount = input.detailedObservations.size - insertedCount
            dao.updateBatch(
                staging.copy(
                    status = "COMMITTED",
                    insertedObservationCount = insertedCount,
                    duplicateObservationCount = duplicateCount,
                ),
            )
            val capturedThrough = maxOfNullable(journal.detailedCapturedThroughEpochMillis, detailedEnd)
            val usableThrough = dao.latestUsableDetailedEpochMillis(
                journalId,
                RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS,
            )
            val advanced = detailedEnd != null &&
                (journal.detailedCapturedThroughEpochMillis == null || detailedEnd > journal.detailedCapturedThroughEpochMillis)
            dao.updateJournal(
                journal.copy(
                    lastAdvancedAtEpochMillis = if (advanced) input.importedAtEpochMillis else journal.lastAdvancedAtEpochMillis,
                    detailedCapturedThroughEpochMillis = capturedThrough,
                    detailedUsableThroughEpochMillis = usableThrough,
                    semanticStartEpochMillis = minOfNullable(journal.semanticStartEpochMillis, semanticStart),
                    semanticEndEpochMillis = maxOfNullable(journal.semanticEndEpochMillis, semanticEnd),
                ),
            )
            val changedStartEpochMillis = minOfNullable(
                minOfNullable(insertedStart, improvedAccuracyStart),
                semanticChangeBounds?.first,
            )
            val changedEndEpochMillis = maxOfNullable(
                maxOfNullable(insertedEnd, improvedAccuracyEnd),
                semanticChangeBounds?.second,
            )
            val needsRouteRefresh = insertedCount > 0 || improvedAccuracyStart != null || semanticChangeBounds != null
            if (needsRouteRefresh) {
                dao.insertRouteProjectionState(
                    RouteProjectionStateEntity(
                        journalId = journalId,
                        sourceRevision = 0,
                        builtRevision = 0,
                        algorithmVersion = 0,
                        buildStatus = "DIRTY",
                    ),
                )
                dao.markRouteProjectionDirty(journalId, changedStartEpochMillis, changedEndEpochMillis)
            }
            JournalImportResult.Committed(
                batchId = batchId,
                insertedObservationCount = insertedCount,
                duplicateObservationCount = duplicateCount,
                semanticSegmentCount = if (shouldStoreSemanticSnapshot) input.semanticSegments.size else 0,
                changeKind = changeKind,
                changedStartEpochMillis = changedStartEpochMillis,
                changedEndEpochMillis = changedEndEpochMillis,
                activeSemanticChanged = semanticChangeBounds != null,
                needsRouteRefresh = needsRouteRefresh,
            )
        }

    /** Returns only the portion where an older snapshot can fill currently absent coverage. */
    private fun uncoveredSemanticBounds(
        incoming: List<SemanticSegmentInput>,
        existing: List<ActiveSemanticSegment>,
    ): Pair<Long, Long>? {
        val covered = existing
            .map { it.startEpochMillis to it.endEpochMillis }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Long, Long>>()) { merged, interval ->
                val last = merged.lastOrNull()
                if (last == null || interval.first > last.second) {
                    merged += interval
                } else if (interval.second > last.second) {
                    merged[merged.lastIndex] = last.first to interval.second
                }
                merged
            }
        var changedStart: Long? = null
        var changedEnd: Long? = null
        for (segment in incoming) {
            var cursor = segment.startEpochMillis
            for (interval in covered) {
                if (interval.second < cursor || interval.first > segment.endEpochMillis) continue
                if (interval.first > cursor) {
                    changedStart = minOfNullable(changedStart, cursor)
                    changedEnd = maxOfNullable(changedEnd, minOf(segment.endEpochMillis, interval.first))
                }
                cursor = maxOf(cursor, interval.second)
            }
            if (cursor < segment.endEpochMillis || (cursor == segment.endEpochMillis && covered.none {
                    cursor in it.first..it.second
                })
            ) {
                changedStart = minOfNullable(changedStart, cursor)
                changedEnd = maxOfNullable(changedEnd, segment.endEpochMillis)
            }
        }
        return changedStart?.let { it to requireNotNull(changedEnd) }
    }

    /** Bounds the symmetric difference between an incoming semantic export and the preferred one. */
    private fun semanticDifferenceBounds(
        incoming: List<SemanticSegmentInput>,
        preferred: List<SemanticSegmentEntity>,
    ): Pair<Long, Long>? {
        val incomingCounts = incoming.groupingBy(::semanticKey).eachCount()
        val preferredCounts = preferred.groupingBy(::semanticKey).eachCount()
        if (incomingCounts == preferredCounts) return null
        var changedStart: Long? = null
        var changedEnd: Long? = null
        incoming.forEach { segment ->
            val key = semanticKey(segment)
            if (incomingCounts.getValue(key) != preferredCounts[key]) {
                changedStart = minOfNullable(changedStart, segment.startEpochMillis)
                changedEnd = maxOfNullable(changedEnd, segment.endEpochMillis)
            }
        }
        preferred.forEach { segment ->
            val key = semanticKey(segment)
            if (preferredCounts.getValue(key) != incomingCounts[key]) {
                changedStart = minOfNullable(changedStart, segment.startEpochMillis)
                changedEnd = maxOfNullable(changedEnd, segment.endEpochMillis)
            }
        }
        return changedStart?.let { it to requireNotNull(changedEnd) }
    }

    private fun semanticKey(segment: SemanticSegmentInput) = SemanticKey(
        segment.startEpochMillis,
        segment.endEpochMillis,
        segment.kind,
        segment.activityType,
        segment.placeId,
        segment.geometryJson,
    )

    private fun semanticKey(segment: SemanticSegmentEntity) = SemanticKey(
        segment.startEpochMillis,
        segment.endEpochMillis,
        segment.kind,
        segment.activityType,
        segment.placeId,
        segment.geometryJson,
    )

    private fun incrementSafely(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1

    private fun decrementSafely(value: Long): Long = if (value == Long.MIN_VALUE) value else value - 1

    /**
     * A duplicate row is redundant only when it cannot improve route accuracy and carries no
     * additional metadata. Conservative retention keeps altitude, speed, and provider provenance.
     */
    private fun existingMetadataDominates(
        candidate: DetailedObservationInput,
        committedAccuracy: Double?,
    ): Boolean {
        if (candidate.altitudeMeters != null || candidate.speedMetersPerSecond != null || candidate.provider != null) {
            return false
        }
        return candidate.accuracyMeters == null ||
            (committedAccuracy != null && committedAccuracy <= candidate.accuracyMeters)
    }

    private fun validate(observation: DetailedObservationInput) {
        require(observation.latitude in -90.0..90.0) { "Latitude is outside the supported range" }
        require(observation.longitude in -180.0..180.0) { "Longitude is outside the supported range" }
        require(observation.latitude.isFinite() && observation.longitude.isFinite()) {
            "Coordinates must be finite"
        }
    }

    private fun observationKey(observation: DetailedObservationInput): String = buildString {
        append(observation.instantEpochMillis)
        append(':')
        append(observation.latitude.toBits().toULong().toString(16))
        append(':')
        append(observation.longitude.toBits().toULong().toString(16))
    }

    private fun <T> deterministicSamples(items: List<T>, limit: Int): List<T> {
        if (items.size <= limit) return items
        return List(limit) { sampleIndex ->
            val itemIndex = sampleIndex.toLong() * (items.lastIndex).toLong() / (limit - 1)
            items[itemIndex.toInt()]
        }
    }

    private fun minOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> minOf(first, second)
    }

    private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private companion object {
        const val OBSERVATION_INSERT_CHUNK_SIZE = 4_096
        const val SEMANTIC_INSERT_CHUNK_SIZE = 1_000
        const val SQLITE_BIND_CHUNK_SIZE = 900
        const val IDENTITY_SAMPLE_SIZE = 32
    }

    private data class SemanticKey(
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val kind: String,
        val activityType: String?,
        val placeId: String?,
        val geometryJson: String?,
    )
}
