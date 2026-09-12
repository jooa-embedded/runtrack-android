package com.runtrack

import com.runtrack.domain.analysis.RunDataAggregator
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunDataAggregatorTest {

    private val aggregator = RunDataAggregator()

    private fun frame(
        tOffsetMs: Long,
        ptaValue: Float = 6f,
        mdf: Float = 90f,
        attenuation: Float = 0.7f
    ) = TelemetryFrame(
        tOffsetMs = tOffsetMs,
        pta = JointLocation.entries.associateWith { ptaValue },
        loadingRate = JointLocation.entries.associateWith { ptaValue * 20f },
        shockAttenuationLeft = attenuation,
        shockAttenuationRight = attenuation,
        medianFreqHz = mdf,
        meanFreqHz = mdf * 1.1f,
        rms = 0.4f,
        signalQuality = 0.95f,
        cadenceSpm = 175
    )

    @Test
    fun `empty frame list yields empty aggregate`() {
        val result = aggregator.aggregate(emptyList(), emptyList())
        assertTrue(result.impactSamples.isEmpty())
        assertTrue(result.emgSamples.isEmpty())
        assertEquals(0f, result.summary.cumulativeImpulseTotal, 0.001f)
    }

    @Test
    fun `aggregates one impact sample per second per joint`() {
        // 3 seconds of data at 5 Hz = 15 frames
        val frames = (0 until 15).map { frame(it * 200L) }
        val result = aggregator.aggregate(frames, emptyList())

        // 3 seconds x 4 joints = 12 impact samples
        assertEquals(12, result.impactSamples.size)
        // 3 emg samples (one per second)
        assertEquals(3, result.emgSamples.size)
    }

    @Test
    fun `summary contains all four joint summaries`() {
        val frames = (0 until 10).map { frame(it * 200L) }
        val result = aggregator.aggregate(frames, emptyList())
        assertEquals(4, result.summary.locationSummaries.size)
        assertEquals(
            JointLocation.entries.toSet(),
            result.summary.locationSummaries.map { it.location }.toSet()
        )
    }

    @Test
    fun `fatigue index is positive when MDF drops over the run`() {
        // MDF declines from 100 down to ~70
        val frames = (0 until 100).map { i ->
            frame(i * 1000L, mdf = 100f - i * 0.3f)
        }
        val result = aggregator.aggregate(frames, emptyList())
        assertTrue(
            "fatigue should be detected when MDF falls",
            result.summary.fatigueIndexPct > 0f
        )
    }

    @Test
    fun `fatigue index is near zero when MDF is stable`() {
        val frames = (0 until 50).map { frame(it * 1000L, mdf = 90f) }
        val result = aggregator.aggregate(frames, emptyList())
        assertTrue(result.summary.fatigueIndexPct < 2f)
    }

    @Test
    fun `cumulative impulse is monotonically non-decreasing`() {
        val frames = (0 until 20).map { frame(it * 250L) }
        val result = aggregator.aggregate(frames, emptyList())
        val leftKnee = result.impactSamples
            .filter { it.location == JointLocation.LEFT_KNEE }
            .sortedBy { it.tOffsetMs }
        for (i in 1 until leftKnee.size) {
            assertTrue(
                "cumulative impulse must never decrease",
                leftKnee[i].cumulativeImpulse >= leftKnee[i - 1].cumulativeImpulse
            )
        }
    }

    @Test
    fun `form break count reflects supplied events`() {
        val frames = (0 until 10).map { frame(it * 200L) }
        // aggregate with an empty event list -> count 0
        val result = aggregator.aggregate(frames, emptyList())
        assertEquals(0, result.summary.formBreakCount)
    }
}
