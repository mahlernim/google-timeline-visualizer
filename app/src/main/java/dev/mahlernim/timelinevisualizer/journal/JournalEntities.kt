package dev.mahlernim.timelinevisualizer.journal

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "journals", primaryKeys = ["id"])
data class JournalEntity(
    val id: String,
    val name: String,
    val isPrimary: Boolean,
    val createdAtEpochMillis: Long,
    val lastAdvancedAtEpochMillis: Long? = null,
    val detailedCapturedThroughEpochMillis: Long? = null,
    val detailedUsableThroughEpochMillis: Long? = null,
    val semanticStartEpochMillis: Long? = null,
    val semanticEndEpochMillis: Long? = null,
    val reminderEligible: Boolean = false,
    val reminderEnabled: Boolean = false,
)

@Entity(
    tableName = "import_batches",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("journalId"),
        Index(value = ["journalId", "sourceHash"], unique = true),
    ],
)
data class ImportBatchEntity(
    @androidx.room.PrimaryKey val id: String,
    val journalId: String,
    val sourceHash: String,
    val sourceName: String?,
    val sourceSize: Long?,
    val importedAtEpochMillis: Long,
    val parserVersion: Int,
    val matchClassification: String,
    val status: String,
    val detailedStartEpochMillis: Long? = null,
    val detailedEndEpochMillis: Long? = null,
    val semanticStartEpochMillis: Long? = null,
    val semanticEndEpochMillis: Long? = null,
    val parsedObservationCount: Int = 0,
    val insertedObservationCount: Int = 0,
    val duplicateObservationCount: Int = 0,
    val rejectedObservationCount: Int = 0,
    val conflictObservationCount: Int = 0,
)

@Entity(
    tableName = "detailed_observations",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("journalId"),
        Index(value = ["journalId", "observationKey"], unique = true),
        Index(value = ["journalId", "instantEpochMillis"]),
    ],
)
data class DetailedObservationEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journalId: String,
    val instantEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val observationKey: String,
)

@Entity(
    tableName = "observation_imports",
    primaryKeys = ["importBatchId", "observationId"],
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["importBatchId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DetailedObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("observationId")],
)
data class ObservationImportEntity(
    val importBatchId: String,
    val observationId: Long,
    val accuracyMeters: Double?,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val provider: String? = null,
)

@Entity(
    tableName = "semantic_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["importBatchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("importBatchId", unique = true)],
)
data class SemanticSnapshotEntity(
    @androidx.room.PrimaryKey val id: String,
    val importBatchId: String,
    val capturedAtEpochMillis: Long,
    val startEpochMillis: Long?,
    val endEpochMillis: Long?,
)

@Entity(
    tableName = "semantic_segments",
    foreignKeys = [
        ForeignKey(
            entity = SemanticSnapshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("snapshotId"),
        Index(value = ["snapshotId", "sourceOrdinal"], unique = true),
        Index(value = ["startEpochMillis", "endEpochMillis"]),
    ],
)
data class SemanticSegmentEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: String,
    val sourceOrdinal: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val kind: String,
    val activityType: String? = null,
    val placeId: String? = null,
    val geometryJson: String? = null,
)

@Entity(
    tableName = "route_projection_states",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RouteProjectionStateEntity(
    @androidx.room.PrimaryKey val journalId: String,
    val sourceRevision: Long,
    val builtRevision: Long,
    val algorithmVersion: Int,
    val buildStatus: String,
    val dirtyStartEpochMillis: Long? = null,
    val dirtyEndEpochMillis: Long? = null,
    val projectionStartEpochMillis: Long? = null,
    val projectionEndExclusiveEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long? = null,
    val spanCount: Int = 0,
    val pointCount: Int = 0,
    val detailedInputCount: Int = 0,
    val detailedUsableCount: Int = 0,
    val semanticUsableCount: Int = 0,
)

@Entity(
    tableName = "route_projection_spans",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntity::class,
            parentColumns = ["id"],
            childColumns = ["journalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["journalId", "ordinal"], unique = true),
        Index(value = ["journalId", "startEpochMillis", "endEpochMillis"]),
    ],
)
data class RouteProjectionSpanEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val journalId: String,
    val ordinal: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val source: String,
    val transitionReason: String? = null,
    val pointCount: Int,
)

@Entity(
    tableName = "route_projection_chunks",
    primaryKeys = ["spanId", "chunkOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = RouteProjectionSpanEntity::class,
            parentColumns = ["id"],
            childColumns = ["spanId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("spanId")],
)
data class RouteProjectionChunkEntity(
    val spanId: Long,
    val chunkOrdinal: Int,
    val formatVersion: Int,
    val pointCount: Int,
    val pointData: ByteArray,
    /** Null only for chunks written before schema 4. */
    val startEpochMillis: Long? = null,
    /** Exclusive when possible. Null only for chunks written before schema 4. */
    val endExclusiveEpochMillis: Long? = null,
)
