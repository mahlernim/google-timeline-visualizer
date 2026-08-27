package dev.mahlernim.timelinevisualizer.journal.route

import dev.mahlernim.timelinevisualizer.model.GeoPoint
import java.time.Duration
import java.time.Instant

enum class RouteSource {
    DETAILED,
    SEMANTIC_PATH,
    SEMANTIC_ENDPOINTS,
    /** A presentation-safe connection between the nearest available observations. */
    INFERRED_TRANSFER,
    GAP,
}

data class RouteSpan(
    val start: Instant,
    val end: Instant,
    val source: RouteSource,
    val points: List<GeoPoint>,
    val transitionReason: String? = null,
) {
    init {
        require(end >= start)
        require(source == RouteSource.GAP || points.isNotEmpty())
        require(source != RouteSource.GAP || points.isEmpty())
    }
}

/** One independently connected semantic source record after snapshot arbitration. */
data class SemanticRoutePath(
    val id: String,
    val start: Instant,
    val end: Instant,
    val points: List<GeoPoint>,
) {
    init {
        require(id.isNotBlank())
        require(end >= start)
        require(points.isNotEmpty())
    }
}

/** Builds a detailed-first route and connects unsupported intervals as inferred transfers. */
object JournalRouteFusion {
    val DEFAULT_DISCONTINUITY: Duration = Duration.ofMinutes(30)

    /** Compatibility API for callers that already have one proven semantic path. */
    fun fuse(
        semanticPoints: List<GeoPoint>,
        detailedPoints: List<GeoPoint>,
        discontinuity: Duration = DEFAULT_DISCONTINUITY,
    ): List<RouteSpan> {
        val ordered = normalize(semanticPoints)
        val paths = if (ordered.isEmpty()) {
            emptyList()
        } else {
            listOf(SemanticRoutePath("compatibility-path", ordered.first().instant, ordered.last().instant, ordered))
        }
        return fuseSemanticPaths(paths, detailedPoints, discontinuity)
    }

    fun fuseSemanticPaths(
        semanticPaths: List<SemanticRoutePath>,
        detailedPoints: List<GeoPoint>,
        discontinuity: Duration = DEFAULT_DISCONTINUITY,
    ): List<RouteSpan> {
        require(!discontinuity.isNegative && !discontinuity.isZero)
        val normalizedPaths = semanticPaths.mapNotNull { path ->
            normalize(path.points).takeIf { it.isNotEmpty() }?.let { path.copy(points = it) }
        }
        val detailedIslands = splitDetailedIslands(resolveDetailedConflicts(detailedPoints), discontinuity)
        if (normalizedPaths.isEmpty()) return detailedOnlySpans(detailedIslands)

        val semanticNodeCount = normalizedPaths.size
        val connections = DisjointSet(semanticNodeCount + detailedIslands.size)
        normalizedPaths.forEachIndexed { pathIndex, path ->
            var islandIndex = firstIslandEndingAtOrAfter(detailedIslands, path.start)
            while (islandIndex < detailedIslands.size && detailedIslands[islandIndex].start <= path.end) {
                connections.union(pathIndex, semanticNodeCount + islandIndex)
                islandIndex += 1
            }
        }

        val candidates = mutableListOf<CandidateSpan>()
        normalizedPaths.forEachIndexed { pathIndex, path ->
            splitOutsideDetailed(path.points, detailedIslands).forEach { points ->
                candidates += CandidateSpan(semanticSpan(points), pathIndex)
            }
        }
        detailedIslands.forEachIndexed { islandIndex, island ->
            candidates += CandidateSpan(
                RouteSpan(island.start, island.end, RouteSource.DETAILED, island.points),
                semanticNodeCount + islandIndex,
            )
        }
        candidates.sortWith(
            compareBy<CandidateSpan> { it.span.start }
                .thenBy { if (it.span.source == RouteSource.DETAILED) 0 else 1 }
                .thenBy { it.span.end },
        )
        return insertInferredTransfers(candidates, connections)
    }

    private fun detailedOnlySpans(islands: List<DetailedIsland>): List<RouteSpan> =
        insertInferredTransfers(
            islands.mapIndexed { index, island ->
                CandidateSpan(
                    RouteSpan(island.start, island.end, RouteSource.DETAILED, island.points),
                    index,
                )
            },
            DisjointSet(islands.size),
        )

    private fun splitOutsideDetailed(
        points: List<GeoPoint>,
        islands: List<DetailedIsland>,
    ): List<List<GeoPoint>> {
        if (islands.isEmpty()) return listOf(points)
        val fragments = mutableListOf<MutableList<GeoPoint>>()
        var current: MutableList<GeoPoint>? = null
        var islandIndex = firstIslandEndingAtOrAfter(islands, points.first().instant)
        points.forEach { point ->
            val active = current
            val previousInstant = active?.lastOrNull()?.instant
            var crossedDetailedIsland = false
            while (islandIndex < islands.size && islands[islandIndex].end < point.instant) {
                if (
                    previousInstant != null &&
                    islands[islandIndex].start > previousInstant &&
                    islands[islandIndex].start < point.instant
                ) {
                    crossedDetailedIsland = true
                }
                islandIndex += 1
            }
            val island = islands.getOrNull(islandIndex)
            if (island != null && point.instant in island.interval) {
                current = null
                return@forEach
            }
            if (
                active == null ||
                crossedDetailedIsland ||
                island?.let {
                    it.start > previousInstant!! && it.start < point.instant
                } == true
            ) {
                current = mutableListOf<GeoPoint>().also(fragments::add)
            }
            current?.add(point)
        }
        return fragments.filter { it.isNotEmpty() }
    }

    private fun firstIslandEndingAtOrAfter(islands: List<DetailedIsland>, instant: Instant): Int {
        var low = 0
        var high = islands.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (islands[middle].end < instant) low = middle + 1 else high = middle
        }
        return low
    }

    private fun resolveDetailedConflicts(points: List<GeoPoint>): List<GeoPoint> = points
        .sortedBy(GeoPoint::instant)
        .groupBy(GeoPoint::instant)
        .mapNotNull { (_, candidates) ->
            val coordinates = candidates.distinctBy { it.latitude.toBits() to it.longitude.toBits() }
            coordinates.singleOrNull()
        }

    private fun splitDetailedIslands(
        points: List<GeoPoint>,
        discontinuity: Duration,
    ): List<DetailedIsland> {
        if (points.isEmpty()) return emptyList()
        val islands = mutableListOf<MutableList<GeoPoint>>()
        points.forEach { point ->
            val current = islands.lastOrNull()
            if (current == null || Duration.between(current.last().instant, point.instant) > discontinuity) {
                islands += mutableListOf(point)
            } else {
                current += point
            }
        }
        return islands.map(::DetailedIsland)
    }

    private fun semanticSpan(points: List<GeoPoint>): RouteSpan = RouteSpan(
        start = points.first().instant,
        end = points.last().instant,
        source = RouteSource.SEMANTIC_PATH,
        points = points,
    )

    private fun insertInferredTransfers(
        candidates: List<CandidateSpan>,
        connections: DisjointSet,
    ): List<RouteSpan> {
        if (candidates.isEmpty()) return emptyList()
        val result = mutableListOf<RouteSpan>()
        var previous: CandidateSpan? = null
        candidates.forEach { next ->
            val prior = previous
            if (prior != null && connections.find(prior.node) != connections.find(next.node)) {
                val from = prior.span.points.last()
                val to = next.span.points.first()
                result += RouteSpan(
                    start = minOf(from.instant, to.instant),
                    end = maxOf(from.instant, to.instant),
                    source = RouteSource.INFERRED_TRANSFER,
                    points = listOf(from, to).distinctBy(::pointKey),
                    transitionReason = if (
                        prior.span.source == RouteSource.DETAILED && next.span.source == RouteSource.DETAILED
                    ) {
                        "Inferred between detailed observation islands"
                    } else {
                        "Inferred between available Timeline records"
                    },
                )
            }
            result += next.span
            previous = next
        }
        return result
    }

    private fun normalize(points: List<GeoPoint>): List<GeoPoint> = points
        .sortedBy(GeoPoint::instant)
        .distinctBy(::pointKey)

    private fun pointKey(point: GeoPoint): Triple<Long, Long, Long> = Triple(
        point.instant.toEpochMilli(),
        point.latitude.toBits(),
        point.longitude.toBits(),
    )

    private data class CandidateSpan(val span: RouteSpan, val node: Int)

    private data class DetailedIsland(val points: List<GeoPoint>) {
        val start: Instant = points.first().instant
        val end: Instant = points.last().instant
        val interval: ClosedRange<Instant> = start..end
    }

    private class DisjointSet(size: Int) {
        private val parents = IntArray(size) { it }

        fun find(value: Int): Int {
            var root = value
            while (parents[root] != root) root = parents[root]
            var current = value
            while (parents[current] != current) {
                val next = parents[current]
                parents[current] = root
                current = next
            }
            return root
        }

        fun union(first: Int, second: Int) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot != secondRoot) parents[secondRoot] = firstRoot
        }
    }
}
