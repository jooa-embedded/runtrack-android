package com.runtrack.data.datasource

import com.runtrack.domain.analysis.RunDataAggregator
import com.runtrack.domain.model.EmgSample
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.ImpactSample
import com.runtrack.domain.model.RunSession
import com.runtrack.domain.model.RunSummary
import com.runtrack.domain.model.TelemetryFrame
import javax.inject.Inject
import kotlin.random.Random

/** A fully-built historical run ready to persist. */
data class GeneratedRun(
    val session: RunSession,
    val impactSamples: List<ImpactSample>,
    val emgSamples: List<EmgSample>,
    val events: List<FormBreakEvent>,
    val summary: RunSummary
)

/**
 * Generates demo history so the list / comparison / trend screens are
 * meaningful on first launch. Fitness gradually improves across sessions,
 * so the trend shows knee impact dropping over time.
 */
class SeedDataGenerator @Inject constructor(
    private val aggregator: RunDataAggregator
) {
    fun generate(count: Int, userWeightKg: Float): List<GeneratedRun> {
        val now = System.currentTimeMillis()
        return (0 until count).map { i ->
            val rng = Random(SEED_BASE + i)
            // oldest (i=0) has the worst form; newest has the best, with light noise
            val fitness = (0.42f + i.toFloat() / (count - 1).coerceAtLeast(1) * 0.40f +
                    (rng.nextFloat() - 0.5f) * 0.08f).coerceIn(0.3f, 0.9f)
            val durationSec = 900 + rng.nextInt(600)            // 25-45 min
            val daysAgo = (count - i) * 2.4 + 1
            val startedAt = now - (daysAgo * DAY_MS).toLong() +
                    rng.nextInt(10).toLong() * 3_600_000L            // random hour offset

            buildRun(SEED_BASE + i, fitness, durationSec, startedAt, userWeightKg, rng)
        }
    }

    private fun buildRun(
        seed: Long,
        fitness: Float,
        durationSec: Int,
        startedAt: Long,
        userWeightKg: Float,
        rng: Random
    ): GeneratedRun {
        val simulator = RunSimulator(seed, fitness, durationSec)

        val frames = ArrayList<TelemetryFrame>(durationSec * 2)
        var ms = 0L
        val totalMs = durationSec * 1000L
        while (ms <= totalMs) {
            frames += simulator.frameAt(ms)
            ms += SEED_STEP_MS
        }
        val events = simulator.allEvents()
        val aggregated = aggregator.aggregate(frames, events)

        val paceKmPerMin = 0.150f + fitness * 0.045f              // faster when fitter
        val distance = durationSec / 60f * paceKmPerMin * 1000f

        val session = RunSession(
            sessionId = 0,
            startedAt = startedAt,
            endedAt = startedAt + totalMs,
            durationSec = durationSec,
            userWeightKgSnapshot = userWeightKg,
            distanceMeters = distance,
            title = TITLES.random(rng),
            note = "",
            createdAt = startedAt
        )
        return GeneratedRun(
            session = session,
            impactSamples = aggregated.impactSamples,
            emgSamples = aggregated.emgSamples,
            events = events,
            summary = aggregated.summary
        )
    }

    private fun <T> List<T>.random(rng: Random): T = this[rng.nextInt(size)]

    private companion object {
        const val SEED_BASE = 9_000L
        const val SEED_STEP_MS = 1000L           // 2 Hz is plenty for seed data
        const val DAY_MS = 86_400_000.0
        val TITLES = listOf("아침 러닝", "저녁 러닝", "한강 러닝", "주말 러닝", "퇴근 후 러닝")
    }
}
