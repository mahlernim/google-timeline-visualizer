package dev.mahlernim.timelinevisualizer.render

import dev.mahlernim.timelinevisualizer.model.Journey
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.floor
import kotlin.math.sqrt

enum class RecapTransportMode { ON_FOOT, RUNNING, CYCLING, DRIVING, TRANSIT, FLYING, MOTORIZED, UNKNOWN }
enum class RecapAnalogyKind { TRACK_LAPS, MARATHONS, EARTH, MOON }
enum class RecapPersonalityId {
    MAIN_CHARACTER_AFTER_DARK, EARLY_BIRD_DLC, WEEKEND_MAIN_CHARACTER, ONE_DAY_PLOT_TWIST,
    STREAK_MODE_UNLOCKED, LONG_HAUL_ENERGY, CHAOS_COORDINATOR, MAIN_CHARACTER_ON_THE_MOVE,
}

/** Aggregate values only. Presentation is rebuilt with the current language and distance unit. */
data class RecapSnapshot(
    val totalDistanceKm: Double,
    val movementDays: Int,
    val dominantMode: RecapTransportMode,
    val dominantModeShare: Double?,
    val personalityId: RecapPersonalityId,
    val personalityValue: Double,
    val analogyKind: RecapAnalogyKind,
    val analogyCount: Double,
)

object RecapAnalyzer {
    private const val MIN_LABEL_COVERAGE = 0.80
    private const val MAX_DAY_SPLIT_HOURS = 6L
    private const val MAX_TIME_SPLIT_HOURS = 6L

    fun analyze(journey: Journey, zone: ZoneId = ZoneId.systemDefault()): RecapSnapshot {
        val daily = sortedMapOf<LocalDate, Double>()
        val transport = mutableMapOf<RecapTransportMode, Double>()
        val intervals = labeledIntervals(journey)
        var intervalIndex = 0
        var total = 0.0
        var reliableDailyKm = 0.0
        var reliableTimeKm = 0.0
        var nightKm = 0.0
        var morningKm = 0.0
        var weekendKm = 0.0
        for (index in 1..journey.points.lastIndex) {
            if (!journey.isConnectedToPrevious(index) || journey.isInferredTransferFromPrevious(index)) continue
            val startKm = journey.cumulativeDistanceKm[index - 1]
            val endKm = journey.cumulativeDistanceKm[index]
            val km = endKm - startKm
            if (!km.isFinite() || km <= 0.0) continue
            total += km
            while (intervalIndex < intervals.size && intervals[intervalIndex].end <= startKm) intervalIndex++
            var cursor = intervalIndex
            while (cursor < intervals.size && intervals[cursor].start < endKm) {
                val interval = intervals[cursor++]
                val overlap = (minOf(endKm, interval.end) - maxOf(startKm, interval.start)).coerceAtLeast(0.0)
                transport[interval.mode] = transport.getOrDefault(interval.mode, 0.0) + overlap
            }
            val from = journey.points[index - 1].instant
            val to = journey.points[index].instant
            val elapsed = Duration.between(from, to)
            if (elapsed.isNegative || elapsed.isZero || elapsed > Duration.ofHours(MAX_DAY_SPLIT_HOURS)) {
                // Endpoint observations do not establish movement on intervening missing days.
                addDaily(daily, from.atZone(zone).toLocalDate(), km / 2)
                addDaily(daily, to.atZone(zone).toLocalDate(), km / 2)
                continue
            }
            reliableDailyKm += km
            val timeReliable = elapsed <= Duration.ofHours(MAX_TIME_SPLIT_HOURS)
            if (timeReliable) reliableTimeKm += km
            val totalMillis = elapsed.toMillis().coerceAtLeast(1L)
            var time = from
            while (time < to) {
                val local = time.atZone(zone)
                val date = local.toLocalDate()
                val next = sequenceOf(5, 9, 21, 24).map { hour ->
                    if (hour == 24) date.plusDays(1).atStartOfDay(zone).toInstant()
                    else date.atTime(hour, 0).atZone(zone).toInstant()
                }.first { it > time }.coerceAtMost(to)
                val part = km * Duration.between(time, next).toMillis() / totalMillis
                addDaily(daily, date, part)
                if (local.dayOfWeek.value >= 6) weekendKm += part
                if (timeReliable) {
                    if (local.hour >= 21 || local.hour < 5) nightKm += part
                    if (local.hour in 5..8) morningKm += part
                }
                time = next
            }
        }
        val classified = transport.values.sum()
        val dominant = transport.maxByOrNull { it.value }
        val mode = dominant?.takeIf {
            total > 0.0 && classified / total >= MIN_LABEL_COVERAGE - 1e-9 && it.value / total >= 0.50
        }
        val personality = personality(daily, total, reliableDailyKm, reliableTimeKm, weekendKm, nightKm, morningKm)
        val analogy = analogy(total)
        return RecapSnapshot(
            totalDistanceKm = total,
            movementDays = daily.size,
            dominantMode = mode?.key ?: RecapTransportMode.UNKNOWN,
            dominantModeShare = mode?.let { (it.value / total).coerceIn(0.0, 1.0) },
            personalityId = personality.first,
            personalityValue = personality.second,
            analogyKind = analogy.first,
            analogyCount = analogy.second,
        )
    }

    private fun addDaily(daily: MutableMap<LocalDate, Double>, date: LocalDate, km: Double) {
        if (km > 0.0) daily[date] = daily.getOrDefault(date, 0.0) + km
    }

    private data class LabelEvent(val at: Double, val mode: RecapTransportMode, val change: Int)
    private data class LabeledInterval(val start: Double, val end: Double, val mode: RecapTransportMode)

    /** Duplicate labels count once. Conflicting simultaneous labels remain unknown. */
    private fun labeledIntervals(journey: Journey): List<LabeledInterval> {
        val events = journey.semanticEpisodes.flatMap { episode ->
            val mode = labeledTransport(episode.activityType) ?: return@flatMap emptyList()
            if (!episode.startKm.isFinite() || !episode.endKm.isFinite() || episode.endKm <= episode.startKm) {
                return@flatMap emptyList()
            }
            listOf(LabelEvent(episode.startKm, mode, 1), LabelEvent(episode.endKm, mode, -1))
        }.sortedBy(LabelEvent::at)
        if (events.isEmpty()) return emptyList()
        val result = mutableListOf<LabeledInterval>()
        val active = mutableMapOf<RecapTransportMode, Int>()
        var previous = events.first().at
        var index = 0
        while (index < events.size) {
            val at = events[index].at
            if (at > previous && active.size == 1) result += LabeledInterval(previous, at, active.keys.single())
            while (index < events.size && events[index].at == at) {
                val event = events[index++]
                val count = active.getOrDefault(event.mode, 0) + event.change
                if (count == 0) active.remove(event.mode) else active[event.mode] = count
            }
            previous = at
        }
        return result
    }

    private fun labeledTransport(value: String?): RecapTransportMode? = when (value?.uppercase(Locale.ROOT)) {
        "WALKING", "ON_FOOT", "HIKING" -> RecapTransportMode.ON_FOOT
        "RUNNING" -> RecapTransportMode.RUNNING
        "CYCLING", "ON_BICYCLE", "BICYCLE" -> RecapTransportMode.CYCLING
        "IN_PASSENGER_VEHICLE", "DRIVING", "IN_CAR" -> RecapTransportMode.DRIVING
        "IN_BUS", "IN_TRAIN", "IN_SUBWAY", "IN_TRAM", "IN_FERRY", "TRANSIT", "IN_RAIL_VEHICLE" -> RecapTransportMode.TRANSIT
        "FLYING", "IN_AIRPLANE" -> RecapTransportMode.FLYING
        "MOTORCYCLING", "IN_MOTORCYCLE", "MOTORIZED", "IN_VEHICLE" -> RecapTransportMode.MOTORIZED
        else -> null
    }

    private fun personality(
        daily: Map<LocalDate, Double>, total: Double, reliableDailyKm: Double, reliableTimeKm: Double,
        weekendKm: Double, nightKm: Double, morningKm: Double,
    ): Pair<RecapPersonalityId, Double> {
        if (total <= 0.0 || daily.isEmpty()) return RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE to 0.0
        data class Candidate(val id: RecapPersonalityId, val value: Double, val score: Double)
        val candidates = mutableListOf<Candidate>()
        fun add(id: RecapPersonalityId, value: Double, threshold: Double, cap: Double, weight: Double = 1.0) {
            if (value >= threshold) candidates += Candidate(id, value, ((value - threshold) / (cap - threshold)).coerceIn(0.0, 1.0) * weight)
        }
        val peak = daily.values.maxOrNull() ?: 0.0
        val mean = total / daily.size
        val span = ChronoUnit.DAYS.between(daily.keys.min(), daily.keys.max()) + 1
        if (daily.size >= 4 && total >= 20.0 && reliableTimeKm / total >= 0.80) {
            add(RecapPersonalityId.MAIN_CHARACTER_AFTER_DARK, nightKm / total, 0.35, 0.80, 1.15)
            add(RecapPersonalityId.EARLY_BIRD_DLC, morningKm / total, 0.35, 0.80, 1.15)
        }
        if (reliableDailyKm / total >= 0.80) {
            if (span >= 14 && daily.size >= 4 && weekendKm >= 20.0) {
                add(RecapPersonalityId.WEEKEND_MAIN_CHARACTER, weekendKm / total, 0.60, 0.95, 1.15)
            }
            if (daily.size >= 3 && peak >= 20.0) {
                add(RecapPersonalityId.ONE_DAY_PLOT_TWIST, peak / total, 0.35, 0.80, 1.20)
            }
            if (daily.size >= 3) add(RecapPersonalityId.LONG_HAUL_ENERGY, mean, 100.0, 500.0, 1.20)
            val substantial = daily.filterValues { it >= 5.0 }.keys.sorted()
            var best = 0
            var streak = 0
            var previous: LocalDate? = null
            substantial.forEach { date ->
                streak = if (previous?.plusDays(1) == date) streak + 1 else 1
                best = maxOf(best, streak)
                previous = date
            }
            if (span >= 30) add(RecapPersonalityId.STREAK_MODE_UNLOCKED, best.toDouble(), 14.0, 45.0, 1.10)
            val cv = sqrt(daily.values.sumOf { (it - mean) * (it - mean) } / daily.size) / mean
            // A single dominant day has its own more specific punchline. Chaos describes
            // uneven travel spread across several days instead of competing for the same fact.
            if (daily.size >= 7 && cv >= 1.25 && peak / total < 0.35) {
                candidates += Candidate(RecapPersonalityId.CHAOS_COORDINATOR, peak / total, ((cv - 1.25) / 1.75).coerceAtMost(1.0) * 1.10)
            }
        }
        return candidates.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { -it.id.ordinal })
            ?.let { it.id to it.value } ?: (RecapPersonalityId.MAIN_CHARACTER_ON_THE_MOVE to daily.size.toDouble())
    }

    private fun analogy(km: Double): Pair<RecapAnalogyKind, Double> {
        fun rounded(value: Double, step: Double) = floor(value / step + 0.5) * step
        return when {
            km < 21.0 -> RecapAnalogyKind.TRACK_LAPS to rounded(km / 0.4, 1.0)
            km < 10_018.75 -> RecapAnalogyKind.MARATHONS to rounded(km / 42.195, 0.5)
            km < 192_200.0 -> RecapAnalogyKind.EARTH to rounded(km / 40_075.0, 0.25)
            else -> RecapAnalogyKind.MOON to rounded(km / 384_400.0, 0.5)
        }
    }
}
