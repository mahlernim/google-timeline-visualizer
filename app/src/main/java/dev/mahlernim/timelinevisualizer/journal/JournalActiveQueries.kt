package dev.mahlernim.timelinevisualizer.journal

import androidx.room.ColumnInfo

/** A committed detailed observation with the best accuracy retained by active provenance. */
data class ActiveDetailedObservation(
    val instantEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/** The committed detailed time range used to avoid probing unrelated Journals. */
data class CommittedDetailedBounds(
    val startEpochMillis: Long?,
    val endEpochMillis: Long?,
)

/** The best committed accuracy already available for one detailed observation. */
data class CommittedObservationAccuracy(
    val observationId: Long,
    val accuracyMeters: Double?,
)

/** A compact lookup result for resolving ignored bulk inserts without per-row queries. */
data class ObservationKeyId(
    val observationKey: String,
    val id: Long,
)

/** A committed semantic segment plus the capture time used to resolve snapshot precedence. */
data class ActiveSemanticSegment(
    val id: Long,
    val snapshotId: String,
    val sourceOrdinal: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val kind: String,
    val activityType: String?,
    val placeId: String?,
    val geometryJson: String?,
    val parserVersion: Int,
    @ColumnInfo(name = "snapshotCapturedAtEpochMillis")
    val snapshotCapturedAtEpochMillis: Long,
)
