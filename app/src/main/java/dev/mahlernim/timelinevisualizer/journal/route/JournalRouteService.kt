package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.data.RawSignalPoint
import dev.mahlernim.timelinevisualizer.data.RawSignalProcessor
import dev.mahlernim.timelinevisualizer.journal.ActiveSemanticSegment
import dev.mahlernim.timelinevisualizer.journal.JournalRepository
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Timeline
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class JournalRoute(
    /** Compatibility projection for consumers that cannot represent route gaps yet. */
    val timeline: Timeline,
    /** Canonical source-aware topology for preview, distance, and export. */
    val spans: List<RouteSpan>,
    val detailedInputCount: Int,
    val detailedUsableCount: Int,
    val semanticUsableCount: Int,
    val cameraEpisodes: List<SemanticCameraEpisode> = emptyList(),
)

/** Global activity context retained separately from the detailed route geometry. */
data class SemanticCameraEpisode(
    val start: Instant,
    val end: Instant,
    val origin: GeoPoint,
    val destination: GeoPoint,
) {
    init {
        require(end >= start)
    }
}

enum class RouteDetail {
    DETAILED,
    SIMPLIFIED,
}

/** The Journal changed while a derived route was being persisted, so this result is stale. */
class StaleJournalRouteBuildException : CancellationException(
    "The Journal changed while its route was being prepared",
)

enum class JournalRoutePreparationStage {
    PREPARING_DETAILED_ROUTES,
    COMBINING_JOURNEY_HISTORY,
    SAVING_FOR_FASTER_STARTS,
}

/** Reconstructs the active Journal projection and applies detailed-first route fusion. */
class JournalRouteService(
    private val repository: JournalRepository,
    private val projectionStore: JournalRouteProjectionStore = JournalRouteProjectionStore(repository.database),
) {
    suspend fun route(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        maximumAccuracyMeters: Double? = RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS,
        discontinuity: Duration = JournalRouteFusion.DEFAULT_DISCONTINUITY,
        routeDetail: RouteDetail = RouteDetail.DETAILED,
        onPreparationStage: suspend (JournalRoutePreparationStage) -> Unit = {},
    ): JournalRoute {
        require(endExclusive > start) { "The route range must not be empty" }
        val usesCanonicalSettings = maximumAccuracyMeters == RawSignalProcessor.DEFAULT_MAXIMUM_ACCURACY_METERS &&
            discontinuity == JournalRouteFusion.DEFAULT_DISCONTINUITY && routeDetail == RouteDetail.DETAILED
        if (!usesCanonicalSettings) {
            return attachCameraEpisodes(
                journalId,
                start,
                endExclusive,
                reconstructWithContext(
                    journalId,
                    start,
                    endExclusive,
                    maximumAccuracyMeters,
                    discontinuity,
                    routeDetail,
                    onPreparationStage,
                ),
            )
        }

        repository.ensureRouteProjectionState(journalId)
        val startMillis = start.toEpochMilli()
        val endMillis = endExclusive.toEpochMilli()
        val state = projectionStore.state(journalId)
        val cachedRangeContainsRequest = state?.projectionStartEpochMillis?.let { cachedStart ->
            val cachedEnd = state.projectionEndExclusiveEpochMillis ?: return@let false
            cachedStart <= startMillis && cachedEnd >= endMillis
        } == true
        val cacheMatchesAlgorithm = state?.algorithmVersion == PROJECTION_ALGORITHM_VERSION
        val cacheIsCurrent = state != null &&
            state.builtRevision == state.sourceRevision && state.buildStatus == "READY"
        val cachedRangeTouchesRequest = state?.projectionStartEpochMillis?.let { cachedStart ->
            val cachedEnd = state.projectionEndExclusiveEpochMillis ?: return@let false
            startMillis <= cachedEnd && endMillis >= cachedStart
        } == true
        val canExtendStoredRoute = cachedRangeTouchesRequest && cacheMatchesAlgorithm && cacheIsCurrent
        val dirtyDoesNotAffectRequest = state?.buildStatus == "DIRTY" &&
            state.dirtyStartEpochMillis != null && state.dirtyEndEpochMillis != null &&
            !overlaps(
                startMillis,
                endMillis,
                state.dirtyStartEpochMillis,
                incrementSafely(state.dirtyEndEpochMillis),
            )
        val mayUseStoredRoute = cachedRangeContainsRequest && cacheMatchesAlgorithm && (
                cacheIsCurrent || dirtyDoesNotAffectRequest ||
                    (state?.buildStatus == "DIRTY" &&
                        state.dirtyStartEpochMillis != null && state.dirtyEndEpochMillis != null)
            )
        // Gate BLOB decoding behind cheap state checks. In particular, migrated lifetime caches
        // from older algorithms are never decoded for a small selected range.
        val previous = if (mayUseStoredRoute) {
            val readWholeProjection = state?.buildStatus == "DIRTY"
            val readStart = if (readWholeProjection) {
                Instant.ofEpochMilli(requireNotNull(state.projectionStartEpochMillis))
            } else {
                start
            }
            val readEnd = if (readWholeProjection) {
                Instant.ofEpochMilli(requireNotNull(state.projectionEndExclusiveEpochMillis))
            } else {
                endExclusive
            }
            projectionStore.read(journalId, readStart, readEnd)?.route
        } else {
            null
        }
        if (
            previous != null && cachedRangeContainsRequest && cacheMatchesAlgorithm &&
            (cacheIsCurrent || dirtyDoesNotAffectRequest)
        ) {
            return attachCameraEpisodes(
                journalId,
                start,
                endExclusive,
                previous.clippedTo(start, endExclusive),
            )
        }

        val rebuilt = if (
            state != null && previous != null &&
            cachedRangeContainsRequest && cacheMatchesAlgorithm &&
            state.dirtyStartEpochMillis != null && state.dirtyEndEpochMillis != null
        ) {
            val cachedStart = requireNotNull(state.projectionStartEpochMillis)
            val cachedEnd = requireNotNull(state.projectionEndExclusiveEpochMillis)
            val dirtyStart = maxOf(cachedStart, state.dirtyStartEpochMillis)
            val dirtyEndExclusive = minOf(cachedEnd, incrementSafely(state.dirtyEndEpochMillis))
            val (refreshStart, refreshEnd) = previous.expandedRefreshWindow(
                Instant.ofEpochMilli(dirtyStart),
                Instant.ofEpochMilli(dirtyEndExclusive),
            )
            val boundedRefreshStart = maxOf(Instant.ofEpochMilli(cachedStart), refreshStart)
            val boundedRefreshEnd = minOf(Instant.ofEpochMilli(cachedEnd), refreshEnd)
            val replacement = reconstructWithContext(
                journalId,
                boundedRefreshStart,
                boundedRefreshEnd,
                maximumAccuracyMeters,
                discontinuity,
                routeDetail,
                onPreparationStage,
            )
            previous.replacingWindow(boundedRefreshStart, boundedRefreshEnd, replacement)
                .clippedTo(Instant.ofEpochMilli(cachedStart), Instant.ofEpochMilli(cachedEnd))
        } else {
            val rebuildStart = if (canExtendStoredRoute) {
                Instant.ofEpochMilli(minOf(requireNotNull(state?.projectionStartEpochMillis), startMillis))
            } else {
                start
            }
            val rebuildEnd = if (canExtendStoredRoute) {
                Instant.ofEpochMilli(maxOf(requireNotNull(state?.projectionEndExclusiveEpochMillis), endMillis))
            } else {
                endExclusive
            }
            // Reconstruct the contiguous union when extending a cache. Splicing independently
            // fused ranges can lose a gap or inferred transfer at the join and would no longer be
            // equivalent to an uncached reconstruction.
            reconstructWithContext(
                journalId,
                rebuildStart,
                rebuildEnd,
                maximumAccuracyMeters,
                discontinuity,
                routeDetail,
                onPreparationStage,
            )
        }
        if (state != null) {
            onPreparationStage(JournalRoutePreparationStage.SAVING_FOR_FASTER_STARTS)
            val stored = projectionStore.replace(
                journalId = journalId,
                expectedSourceRevision = state.sourceRevision,
                algorithmVersion = PROJECTION_ALGORITHM_VERSION,
                route = rebuilt,
                projectionStartEpochMillis = when {
                    cachedRangeContainsRequest && cacheMatchesAlgorithm -> requireNotNull(state.projectionStartEpochMillis)
                    canExtendStoredRoute -> minOf(requireNotNull(state.projectionStartEpochMillis), startMillis)
                    else -> startMillis
                },
                projectionEndExclusiveEpochMillis = when {
                    cachedRangeContainsRequest && cacheMatchesAlgorithm -> requireNotNull(state.projectionEndExclusiveEpochMillis)
                    canExtendStoredRoute -> maxOf(requireNotNull(state.projectionEndExclusiveEpochMillis), endMillis)
                    else -> endMillis
                },
            )
            if (!stored) throw StaleJournalRouteBuildException()
        }
        return attachCameraEpisodes(
            journalId,
            start,
            endExclusive,
            rebuilt.clippedTo(start, endExclusive),
        )
    }

    private suspend fun attachCameraEpisodes(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        route: JournalRoute,
    ): JournalRoute {
        val rows = repository.activeSemanticActivitySegments(
            journalId,
            start.toEpochMilli(),
            endExclusive.toEpochMilli(),
        )
        if (rows.isEmpty()) return route

        val coveredByNewerSnapshots = MergedMillisIntervals()
        val episodes = mutableListOf<SemanticCameraEpisode>()
        rows.groupBy { it.snapshotCapturedAtEpochMillis to it.snapshotId }.forEach { (_, snapshotRows) ->
            val records = snapshotRecords(snapshotRows).filter { record ->
                record.kind == STRUCTURED_ACTIVITY_KIND || record.kind == STRUCTURED_ACTIVITY_AND_VISIT_KIND
            }
            records.forEach recordLoop@ { record ->
                if (coveredByNewerSnapshots.overlaps(record.interval)) return@recordLoop
                val origin = record.points.firstOrNull() ?: return@recordLoop
                val destination = record.points.lastOrNull() ?: return@recordLoop
                episodes += SemanticCameraEpisode(
                    start = Instant.ofEpochMilli(record.startEpochMillis),
                    end = Instant.ofEpochMilli(record.endEpochMillis),
                    origin = origin,
                    destination = destination,
                )
            }
            coveredByNewerSnapshots.addAll(records.map(StoredSemanticRecord::interval))
        }
        return route.copy(cameraEpisodes = episodes.sortedBy(SemanticCameraEpisode::start))
    }

    private suspend fun reconstructWithContext(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        maximumAccuracyMeters: Double?,
        discontinuity: Duration,
        routeDetail: RouteDetail,
        onPreparationStage: suspend (JournalRoutePreparationStage) -> Unit,
    ): JournalRoute {
        val contextMillis = discontinuity.toMillis()
        val queryStart = Instant.ofEpochMilli(subtractSafely(start.toEpochMilli(), contextMillis))
        val queryEnd = Instant.ofEpochMilli(addSafely(endExclusive.toEpochMilli(), contextMillis))
        return reconstruct(
            journalId,
            queryStart,
            queryEnd,
            maximumAccuracyMeters,
            discontinuity,
            routeDetail,
            onPreparationStage,
        ).clippedTo(start, endExclusive)
    }

    private suspend fun reconstruct(
        journalId: String,
        start: Instant,
        endExclusive: Instant,
        maximumAccuracyMeters: Double?,
        discontinuity: Duration,
        routeDetail: RouteDetail,
        onPreparationStage: suspend (JournalRoutePreparationStage) -> Unit,
    ): JournalRoute {
        val startMillis = start.toEpochMilli()
        val endMillis = endExclusive.toEpochMilli()
        onPreparationStage(JournalRoutePreparationStage.PREPARING_DETAILED_ROUTES)
        val detailedRows = repository.activeDetailedObservations(journalId, startMillis, endMillis)
        val detailed = RawSignalProcessor.process(
            source = detailedRows.map { row ->
                RawSignalPoint(
                    point = GeoPoint(
                        instant = Instant.ofEpochMilli(row.instantEpochMillis),
                        latitude = row.latitude,
                        longitude = row.longitude,
                    ),
                    accuracyMeters = row.accuracyMeters,
                )
            },
            maximumAccuracyMeters = maximumAccuracyMeters,
        ).points
        onPreparationStage(JournalRoutePreparationStage.COMBINING_JOURNEY_HISTORY)
        val boundedSemanticRows = repository.activeSemanticSegments(journalId, startMillis, endMillis)
        val semanticPaths = coverageAwareSemanticPaths(
            expandOverlappingSemanticComponents(journalId, boundedSemanticRows),
        )
        val spans = JournalRouteFusion.fuseSemanticPaths(
            semanticPaths = semanticPaths,
            detailedPoints = if (routeDetail == RouteDetail.DETAILED || semanticPaths.isEmpty()) detailed else emptyList(),
            discontinuity = discontinuity,
        )
        val flattened = spans.asSequence()
            .filter { it.source != RouteSource.GAP }
            .flatMap { it.points.asSequence() }
            .distinctBy(::pointKey)
            .sortedBy(GeoPoint::instant)
            .toList()
        return JournalRoute(
            timeline = Timeline(flattened),
            spans = spans,
            detailedInputCount = detailedRows.size,
            detailedUsableCount = detailed.size,
            semanticUsableCount = semanticPaths.sumOf { it.points.size },
        )
    }

    private fun incrementSafely(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1

    private fun addSafely(value: Long, amount: Long): Long =
        if (amount > 0 && value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount

    private fun subtractSafely(value: Long, amount: Long): Long =
        if (amount > 0 && value < Long.MIN_VALUE + amount) Long.MIN_VALUE else value - amount

    private fun overlaps(firstStart: Long, firstEnd: Long, secondStart: Long, secondEnd: Long): Boolean =
        firstStart < secondEnd && secondStart < firstEnd

    private suspend fun expandOverlappingSemanticComponents(
        journalId: String,
        boundedRows: List<ActiveSemanticSegment>,
    ): List<ActiveSemanticSegment> {
        if (boundedRows.isEmpty()) return boundedRows
        val groupedParts = boundedRows.mapNotNull { row ->
            val geometry = SemanticGeometryCodec.decodeGeometry(row.geometryJson)
            geometry.continuityGroup?.let { group -> ComponentKey(row.snapshotId, group) to geometry }
        }.groupBy({ it.first }, { it.second })
        val incompleteGroups = groupedParts.mapNotNull { (key, parts) ->
            val expected = parts.mapNotNull(SemanticGeometryCodec.Geometry::partCount).maxOrNull() ?: 1
            key.takeIf { parts.mapNotNull(SemanticGeometryCodec.Geometry::partIndex).distinct().size < expected }
        }.toSet()
        // Legacy rows were already flattened into time-bounded chunks at import. Re-reading the
        // complete snapshot can turn a one-week video into a decade-long decode without adding
        // points inside the requested window. Structured multi-part records are different. Their
        // complete geometry is needed for direction arbitration, so expand only those groups.
        val snapshotsToExpand = incompleteGroups.map(ComponentKey::snapshotId).toSet()
        if (snapshotsToExpand.isEmpty()) return boundedRows

        val siblings = repository.activeSemanticSegmentsForSnapshots(journalId, snapshotsToExpand)
            .filter { row ->
                val group = SemanticGeometryCodec.decodeGeometry(row.geometryJson).continuityGroup
                group != null && ComponentKey(row.snapshotId, group) in incompleteGroups
            }
        return (boundedRows + siblings).distinctBy(ActiveSemanticSegment::id).sortedWith(
            compareByDescending<ActiveSemanticSegment> { it.snapshotCapturedAtEpochMillis }
                .thenByDescending(ActiveSemanticSegment::snapshotId)
                .thenBy(ActiveSemanticSegment::sourceOrdinal),
        )
    }

    private fun coverageAwareSemanticPaths(
        segments: List<ActiveSemanticSegment>,
    ): List<SemanticRoutePath> {
        val coveredByNewerSnapshots = MergedMillisIntervals()
        val selected = mutableListOf<SemanticRoutePath>()
        segments.groupBy { it.snapshotCapturedAtEpochMillis to it.snapshotId }.forEach { (_, snapshotRows) ->
            val reconciled = reconcileSnapshotRecords(
                snapshotRecords(snapshotRows),
            )
            reconciled.forEach { record ->
                record.fragmentsOutside(coveredByNewerSnapshots).forEachIndexed { index, fragment ->
                    selected += fragment.toRoutePath("${fragment.id}:selected:$index")
                }
            }
            coveredByNewerSnapshots.addAll(reconciled.map(StoredSemanticRecord::interval))
        }
        return selected.sortedWith(compareBy<SemanticRoutePath> { it.start }.thenBy(SemanticRoutePath::id))
    }

    /**
     * Selects one semantic geometry history per covered instant without flattening competing
     * histories together. A standalone path wins ambiguous overlap because it normally carries
     * more shape than activity or visit endpoints. Repeated coordinate conflicts or a clear
     * end-to-start reversal are high-confidence signals that it belongs to a competing history.
     */
    private fun reconcileSnapshotRecords(records: List<StoredSemanticRecord>): List<StoredSemanticRecord> {
        val preferred = records.filter { it.kind in PREFERRED_SEMANTIC_KINDS }
        val standalonePaths = records.filter { it.kind == STRUCTURED_PATH_KIND }
        if (preferred.isEmpty() || standalonePaths.isEmpty()) return records

        val preferredIntervals = MergedMillisIntervals(preferred.map(StoredSemanticRecord::interval))
        val preferredPoints = normalize(preferred.flatMap(StoredSemanticRecord::points))
        val directionalComponents = directionalComponents(preferred)
        val acceptedCoverage = MergedMillisIntervals()
        val acceptedPaths = mutableListOf<StoredSemanticRecord>()

        standalonePaths.forEach { path ->
            path.fragmentsOutside(acceptedCoverage).forEach { candidate ->
                if (!preferredIntervals.overlaps(candidate.interval)) {
                    acceptedPaths += candidate
                } else if (hasHighConfidenceCompetingHistory(candidate, directionalComponents, preferredPoints)) {
                    acceptedPaths += candidate.fragmentsOutside(preferredIntervals)
                } else {
                    acceptedPaths += candidate.withBoundaryAnchors(preferredPoints)
                    acceptedCoverage.add(candidate.interval)
                }
            }
        }

        val retainedPreferred = preferred.flatMap { it.fragmentsOutside(acceptedCoverage) }
        val otherRecords = records.filter { record ->
            record.kind !in PREFERRED_SEMANTIC_KINDS && record.kind != STRUCTURED_PATH_KIND
        }
        return retainedPreferred + acceptedPaths + otherRecords
    }

    private fun hasHighConfidenceCompetingHistory(
        path: StoredSemanticRecord,
        directionalComponents: List<DirectionalComponent>,
        preferredPoints: List<GeoPoint>,
    ): Boolean {
        if (path.points.isEmpty() || preferredPoints.isEmpty()) return false
        if (hasReversedBoundaryOrder(path, directionalComponents)) return true
        val relevantStart = preferredPoints.lowerBound(path.startEpochMillis)
        val relevantEnd = preferredPoints.upperBound(path.endEpochMillis)
        if (relevantStart >= relevantEnd) return false

        var pathIndex = 0
        var preferredIndex = relevantStart
        var sharedInstants = 0
        var strongConflicts = 0
        while (pathIndex < path.points.size && preferredIndex < relevantEnd) {
            val pathPoint = path.points[pathIndex]
            val preferredPoint = preferredPoints[preferredIndex]
            val pathTime = pathPoint.instant.toEpochMilli()
            val preferredTime = preferredPoint.instant.toEpochMilli()
            when {
                pathTime < preferredTime -> pathIndex += 1
                pathTime > preferredTime -> preferredIndex += 1
                else -> {
                    var pathGroupEnd = pathIndex + 1
                    while (
                        pathGroupEnd < path.points.size &&
                        path.points[pathGroupEnd].instant.toEpochMilli() == pathTime
                    ) {
                        pathGroupEnd += 1
                    }
                    var preferredGroupEnd = preferredIndex + 1
                    while (
                        preferredGroupEnd < relevantEnd &&
                        preferredPoints[preferredGroupEnd].instant.toEpochMilli() == preferredTime
                    ) {
                        preferredGroupEnd += 1
                    }
                    // Multiple coordinates at one instant are ambiguous source evidence, so they
                    // cannot justify discarding a potentially useful path.
                    if (pathGroupEnd == pathIndex + 1 && preferredGroupEnd == preferredIndex + 1) {
                        sharedInstants += 1
                        if (distanceMeters(pathPoint, preferredPoint) >= STRONG_CONFLICT_DISTANCE_METERS) {
                            strongConflicts += 1
                        }
                    }
                    pathIndex = pathGroupEnd
                    preferredIndex = preferredGroupEnd
                }
            }
        }
        return strongConflicts >= MINIMUM_STRONG_CONFLICTS && strongConflicts * 2 >= sharedInstants
    }

    private fun hasReversedBoundaryOrder(
        path: StoredSemanticRecord,
        components: List<DirectionalComponent>,
    ): Boolean {
        if (components.isEmpty()) return false
        val observations = mutableMapOf<Int, DirectionalObservation>()
        path.points.forEach { point ->
            val componentIndex = components.indexAt(point.instant.toEpochMilli())
            if (componentIndex >= 0) {
                val existing = observations[componentIndex]
                observations[componentIndex] = if (existing == null) {
                    DirectionalObservation(point, point)
                } else {
                    existing.copy(last = point)
                }
            }
        }
        return observations.any { (componentIndex, observation) ->
            if (observation.first.instant == observation.last.instant) return@any false
            val component = components[componentIndex]
            val anchorDistance = distanceMeters(component.startAnchor, component.endAnchor)
            if (anchorDistance < MINIMUM_DIRECTIONAL_ANCHOR_DISTANCE_METERS) return@any false
            val firstToStart = distanceMeters(observation.first, component.startAnchor)
            val firstToEnd = distanceMeters(observation.first, component.endAnchor)
            val lastToStart = distanceMeters(observation.last, component.startAnchor)
            val lastToEnd = distanceMeters(observation.last, component.endAnchor)
            firstToEnd <= DIRECTIONAL_ANCHOR_MATCH_METERS &&
                lastToStart <= DIRECTIONAL_ANCHOR_MATCH_METERS &&
                firstToEnd + DIRECTIONAL_ORDER_MARGIN_METERS < firstToStart &&
                lastToStart + DIRECTIONAL_ORDER_MARGIN_METERS < lastToEnd
        }
    }

    private fun directionalComponents(records: List<StoredSemanticRecord>): List<DirectionalComponent> {
        val ordered = records.mapNotNull { record ->
            val startAnchor = record.points.firstOrNull() ?: return@mapNotNull null
            val endAnchor = record.points.lastOrNull() ?: return@mapNotNull null
            DirectionalComponent(record.startEpochMillis, record.endEpochMillis, startAnchor, endAnchor)
        }.sortedWith(compareBy<DirectionalComponent> { it.startEpochMillis }.thenBy { it.endEpochMillis })
        val components = ArrayList<DirectionalComponent>(ordered.size)
        ordered.forEach { next ->
            val previous = components.lastOrNull()
            if (previous == null || next.startEpochMillis >= previous.endEpochMillis) {
                components += next
            } else if (next.endEpochMillis > previous.endEpochMillis) {
                components[components.lastIndex] = previous.copy(
                    endEpochMillis = next.endEpochMillis,
                    endAnchor = next.endAnchor,
                )
            }
        }
        return components
    }

    private fun List<DirectionalComponent>.indexAt(epochMillis: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].startEpochMillis <= epochMillis) low = middle + 1 else high = middle
        }
        val index = low - 1
        return if (index >= 0 && epochMillis <= this[index].endEpochMillis) index else -1
    }

    private fun StoredSemanticRecord.withBoundaryAnchors(
        preferredPoints: List<GeoPoint>,
    ): StoredSemanticRecord {
        if (points.isEmpty() || preferredPoints.isEmpty()) return this
        val enriched = ArrayList<GeoPoint>(points.size + 2)
        val beforeIndex = preferredPoints.lowerBound(points.first().instant.toEpochMilli()) - 1
        preferredPoints.getOrNull(beforeIndex)
            ?.takeIf { it.instant.toEpochMilli() >= startEpochMillis }
            ?.let(enriched::add)
        enriched += points
        val afterIndex = preferredPoints.upperBound(points.last().instant.toEpochMilli())
        preferredPoints.getOrNull(afterIndex)
            ?.takeIf { it.instant.toEpochMilli() <= endEpochMillis }
            ?.let(enriched::add)
        return copy(points = normalize(enriched))
    }

    private fun List<GeoPoint>.lowerBound(epochMillis: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].instant.toEpochMilli() < epochMillis) low = middle + 1 else high = middle
        }
        return low
    }

    private fun List<GeoPoint>.upperBound(epochMillis: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].instant.toEpochMilli() <= epochMillis) low = middle + 1 else high = middle
        }
        return low
    }

    private fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
        val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        val bounded = haversine.coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(bounded), sqrt(1 - bounded))
    }

    private fun snapshotRecords(
        rows: List<ActiveSemanticSegment>,
    ): List<StoredSemanticRecord> {
        if (rows.isEmpty()) return emptyList()
        val ordered = rows.sortedBy(ActiveSemanticSegment::sourceOrdinal)
        if (ordered.first().parserVersion <= LEGACY_FLATTENED_PARSER_VERSION) {
            val points = ordered.flatMap { SemanticGeometryCodec.decode(it.geometryJson) }
            if (points.isEmpty()) return emptyList()
            return listOf(
                StoredSemanticRecord(
                    id = "${ordered.first().snapshotId}:legacy",
                    kind = LEGACY_PATH_KIND,
                    startEpochMillis = ordered.minOf(ActiveSemanticSegment::startEpochMillis),
                    endEpochMillis = ordered.maxOf(ActiveSemanticSegment::endEpochMillis),
                    points = points,
                ),
            )
        }

        val decoded = ordered.mapNotNull { row ->
            val geometry = SemanticGeometryCodec.decodeGeometry(row.geometryJson)
            val points = geometry.points
            if (points.isEmpty()) null else DecodedRow(row, geometry, points)
        }
        val grouped = decoded.groupBy { decodedRow ->
            decodedRow.geometry.continuityGroup?.let { "group:$it" } ?: "row:${decodedRow.row.id}"
        }
        return grouped.values.flatMap { parts ->
            coalescedRecord(parts)?.let(::listOf)
                ?: parts.map { part -> part.toRecord() }
        }
    }

    private fun coalescedRecord(
        parts: List<DecodedRow>,
    ): StoredSemanticRecord? {
        val ordered = parts.sortedBy { it.geometry.partIndex }
        val expectedCount = ordered.firstOrNull()?.geometry?.partCount ?: return null
        if (
            expectedCount != ordered.size ||
            ordered.map { it.geometry.partIndex } != (0 until expectedCount).toList() ||
            ordered.any { it.geometry.partCount != expectedCount }
        ) {
            return null
        }
        return StoredSemanticRecord(
            id = "${ordered.first().row.snapshotId}:${ordered.first().geometry.continuityGroup}",
            kind = ordered.first().row.kind,
            startEpochMillis = ordered.minOf { it.row.startEpochMillis },
            endEpochMillis = ordered.maxOf { it.row.endEpochMillis },
            points = normalize(ordered.flatMap(DecodedRow::points)),
        )
    }

    private fun DecodedRow.toRecord() = StoredSemanticRecord(
        id = "${row.snapshotId}:row:${row.id}",
        kind = row.kind,
        startEpochMillis = row.startEpochMillis,
        endEpochMillis = row.endEpochMillis,
        points = points,
    )

    private fun StoredSemanticRecord.fragmentsOutside(
        excluded: MergedMillisIntervals,
    ): List<StoredSemanticRecord> {
        if (!excluded.overlaps(interval)) return listOf(this)
        val fragments = mutableListOf<MutableList<GeoPoint>>()
        var current: MutableList<GeoPoint>? = null
        var exclusionIndex = excluded.firstEndingAtOrAfter(points.first().instant.toEpochMilli())
        points.forEach { point ->
            val instant = point.instant.toEpochMilli()
            val active = current
            val previousInstant = active?.lastOrNull()?.instant?.toEpochMilli()
            var crossedExcludedInterval = false
            while (
                exclusionIndex < excluded.size &&
                excluded[exclusionIndex].endInclusive < instant
            ) {
                if (
                    previousInstant != null &&
                    excluded[exclusionIndex].start > previousInstant &&
                    excluded[exclusionIndex].start < instant
                ) {
                    crossedExcludedInterval = true
                }
                exclusionIndex += 1
            }
            val exclusion = excluded.getOrNull(exclusionIndex)
            if (exclusion != null && instant in exclusion) {
                current = null
                return@forEach
            }
            if (
                active == null ||
                crossedExcludedInterval ||
                exclusion?.start?.let { start ->
                    start > previousInstant!! && start < instant
                } == true
            ) {
                current = mutableListOf<GeoPoint>().also(fragments::add)
            }
            current?.add(point)
        }
        return fragments.filter { it.isNotEmpty() }.mapIndexed { index, fragment ->
            copy(
                id = "$id:fragment:$index",
                startEpochMillis = fragment.first().instant.toEpochMilli(),
                endEpochMillis = fragment.last().instant.toEpochMilli(),
                points = fragment,
            )
        }
    }

    private fun StoredSemanticRecord.toRoutePath(routeId: String) = SemanticRoutePath(
        id = routeId,
        start = Instant.ofEpochMilli(startEpochMillis),
        end = Instant.ofEpochMilli(endEpochMillis),
        points = points,
    )

    private fun normalize(points: List<GeoPoint>): List<GeoPoint> = points
        .sortedBy(GeoPoint::instant)
        .distinctBy(::pointKey)

    private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
        point.instant.toEpochMilli(),
        point.latitude.toBits(),
        point.longitude.toBits(),
    )

    private data class DecodedRow(
        val row: ActiveSemanticSegment,
        val geometry: SemanticGeometryCodec.Geometry,
        val points: List<GeoPoint>,
    )

    private data class ComponentKey(
        val snapshotId: String,
        val continuityGroup: String,
    )

    private data class StoredSemanticRecord(
        val id: String,
        val kind: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val points: List<GeoPoint>,
    ) {
        val interval = MillisInterval(startEpochMillis, endEpochMillis)
    }

    private data class DirectionalComponent(
        val startEpochMillis: Long,
        val endEpochMillis: Long,
        val startAnchor: GeoPoint,
        val endAnchor: GeoPoint,
    )

    private data class DirectionalObservation(
        val first: GeoPoint,
        val last: GeoPoint,
    )

    private data class MillisInterval(
        val start: Long,
        val endInclusive: Long,
    ) {
        operator fun contains(value: Long): Boolean = value in start..endInclusive

        fun overlaps(other: MillisInterval): Boolean = start <= other.endInclusive && other.start <= endInclusive
    }

    /** Sorted, non-overlapping intervals used for linear point exclusion sweeps. */
    private class MergedMillisIntervals(intervals: List<MillisInterval> = emptyList()) {
        private val values = ArrayList(merge(intervals))

        val size: Int get() = values.size

        operator fun get(index: Int): MillisInterval = values[index]

        fun getOrNull(index: Int): MillisInterval? = values.getOrNull(index)

        fun overlaps(interval: MillisInterval): Boolean {
            val low = firstEndingAtOrAfter(interval.start)
            return low < values.size && values[low].start <= interval.endInclusive
        }

        fun firstEndingAtOrAfter(epochMillis: Long): Int {
            var low = 0
            var high = values.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (values[middle].endInclusive < epochMillis) low = middle + 1 else high = middle
            }
            return low
        }

        /** Adds one interval without sorting and merging the complete existing coverage again. */
        fun add(interval: MillisInterval) {
            if (values.isEmpty()) {
                values.add(interval)
                return
            }

            val firstOverlap = firstEndingAtOrAfter(interval.start)
            if (firstOverlap == values.size) {
                values.add(interval)
                return
            }
            if (interval.endInclusive < values[firstOverlap].start) {
                values.add(firstOverlap, interval)
                return
            }

            var mergedStart = minOf(interval.start, values[firstOverlap].start)
            var mergedEnd = maxOf(interval.endInclusive, values[firstOverlap].endInclusive)
            var afterOverlap = firstOverlap + 1
            while (afterOverlap < values.size && values[afterOverlap].start <= mergedEnd) {
                mergedStart = minOf(mergedStart, values[afterOverlap].start)
                mergedEnd = maxOf(mergedEnd, values[afterOverlap].endInclusive)
                afterOverlap += 1
            }
            values[firstOverlap] = MillisInterval(mergedStart, mergedEnd)
            if (afterOverlap > firstOverlap + 1) {
                values.subList(firstOverlap + 1, afterOverlap).clear()
            }
        }

        fun addAll(intervals: List<MillisInterval>) {
            if (intervals.isEmpty()) return
            val combined = mergeSorted(values, merge(intervals))
            values.clear()
            values.addAll(combined)
        }

        private fun merge(intervals: List<MillisInterval>): List<MillisInterval> {
            if (intervals.isEmpty()) return emptyList()
            val ordered = intervals.sortedWith(compareBy<MillisInterval> { it.start }.thenBy { it.endInclusive })
            val merged = ArrayList<MillisInterval>(ordered.size)
            ordered.forEach { next ->
                val previous = merged.lastOrNull()
                if (previous == null || next.start > previous.endInclusive) {
                    merged += next
                } else if (next.endInclusive > previous.endInclusive) {
                    merged[merged.lastIndex] = MillisInterval(previous.start, next.endInclusive)
                }
            }
            return merged
        }

        private fun mergeSorted(
            first: List<MillisInterval>,
            second: List<MillisInterval>,
        ): List<MillisInterval> {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first
            val mergedInput = ArrayList<MillisInterval>(first.size + second.size)
            var firstIndex = 0
            var secondIndex = 0
            while (firstIndex < first.size || secondIndex < second.size) {
                if (
                    secondIndex >= second.size ||
                    firstIndex < first.size && first[firstIndex].start <= second[secondIndex].start
                ) {
                    mergedInput += first[firstIndex++]
                } else {
                    mergedInput += second[secondIndex++]
                }
            }
            val result = ArrayList<MillisInterval>(mergedInput.size)
            mergedInput.forEach { next ->
                val previous = result.lastOrNull()
                if (previous == null || next.start > previous.endInclusive) {
                    result += next
                } else if (next.endInclusive > previous.endInclusive) {
                    result[result.lastIndex] = MillisInterval(previous.start, next.endInclusive)
                }
            }
            return result
        }
    }

    private companion object {
        const val PROJECTION_ALGORITHM_VERSION = 3
        const val LEGACY_FLATTENED_PARSER_VERSION = 1
        const val LEGACY_PATH_KIND = "TIMELINE_PATH"
        const val STRUCTURED_PATH_KIND = "PATH"
        const val STRUCTURED_ACTIVITY_KIND = "ACTIVITY"
        const val STRUCTURED_ACTIVITY_AND_VISIT_KIND = "ACTIVITY_AND_VISIT"
        const val MINIMUM_STRONG_CONFLICTS = 2
        const val STRONG_CONFLICT_DISTANCE_METERS = 5_000.0
        const val MINIMUM_DIRECTIONAL_ANCHOR_DISTANCE_METERS = 1_000.0
        const val DIRECTIONAL_ANCHOR_MATCH_METERS = 5_000.0
        const val DIRECTIONAL_ORDER_MARGIN_METERS = 500.0
        const val EARTH_RADIUS_METERS = 6_371_000.0
        val PREFERRED_SEMANTIC_KINDS = setOf("ACTIVITY", "VISIT", "ACTIVITY_AND_VISIT")
    }
}
