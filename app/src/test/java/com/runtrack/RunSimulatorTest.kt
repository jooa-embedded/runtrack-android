package com.runtrack

import com.runtrack.data.datasource.RunSimulator
import com.runtrack.domain.model.JointLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSimulatorTest {

    @Test
    fun `same seed produces identical frames (deterministic)`() {
        val a = RunSimulator(seed = 42L, fitness = 0.6f, runDurationSec = 600)
        val b = RunSimulator(seed = 42L, fitness = 0.6f, runDurationSec = 600)
        val fa = a.frameAt(30_000L)
        val fb = b.frameAt(30_000L)
        assertEquals(fa.medianFreqHz, fb.medianFreqHz, 0.0001f)
        assertEquals(
            fa.pta[JointLocation.LEFT_KNEE],
            fb.pta[JointLocation.LEFT_KNEE]
        )
        assertEquals(fa.cadenceSpm, fb.cadenceSpm)
    }

    @Test
    fun `frame exposes a pta value for every joint`() {
        val sim = RunSimulator(seed = 1L, fitness = 0.5f, runDurationSec = 600)
        val frame = sim.frameAt(10_000L)
        JointLocation.entries.forEach { joint ->
            assertTrue(
                "missing or non-positive PTA for $joint",
                (frame.pta[joint] ?: 0f) > 0f
            )
        }
    }

    @Test
    fun `attenuation stays within physical 0 to 1 bounds`() {
        val sim = RunSimulator(seed = 7L, fitness = 0.4f, runDurationSec = 1200)
        for (t in 0..1_200_000L step 60_000L) {
            val f = sim.frameAt(t)
            assertTrue(f.shockAttenuationLeft in 0f..1f)
            assertTrue(f.shockAttenuationRight in 0f..1f)
            assertTrue(f.signalQuality in 0f..1f)
        }
    }

    @Test
    fun `median frequency declines as the run progresses (fatigue)`() {
        val sim = RunSimulator(seed = 3L, fitness = 0.5f, runDurationSec = 1200)
        val early = (0..20).map { sim.frameAt(it * 1000L).medianFreqHz }.average()
        val late = (1180..1200).map { sim.frameAt(it * 1000L).medianFreqHz }.average()
        assertTrue("MDF should fall over a long run", late < early)
    }

    @Test
    fun `higher fitness yields lower joint impact`() {
        val lowFit = RunSimulator(seed = 5L, fitness = 0.3f, runDurationSec = 600)
        val highFit = RunSimulator(seed = 5L, fitness = 0.9f, runDurationSec = 600)
        // average several frames to smooth out noise
        val lowAvg = (0..30).map {
            lowFit.frameAt(it * 1000L).pta[JointLocation.LEFT_KNEE] ?: 0f
        }.average()
        val highAvg = (0..30).map {
            highFit.frameAt(it * 1000L).pta[JointLocation.LEFT_KNEE] ?: 0f
        }.average()
        assertTrue("fitter runner should have lower knee impact", highAvg < lowAvg)
    }

    @Test
    fun `generates at least one form-break event`() {
        val sim = RunSimulator(seed = 11L, fitness = 0.5f, runDurationSec = 1200)
        assertTrue(sim.allEvents().isNotEmpty())
    }

    @Test
    fun `form-break events fall within the run duration`() {
        val durationSec = 900
        val sim = RunSimulator(seed = 13L, fitness = 0.5f, runDurationSec = durationSec)
        sim.allEvents().forEach { event ->
            assertTrue(
                "event at ${event.tOffsetMs}ms is outside the run",
                event.tOffsetMs in 0L..(durationSec * 1000L)
            )
            assertTrue(event.durationMs > 0L)
        }
    }

    @Test
    fun `eventStartingIn only returns events inside the window`() {
        val sim = RunSimulator(seed = 17L, fitness = 0.5f, runDurationSec = 1200)
        val events = sim.allEvents()
        val first = events.first()
        // a window that contains the first event's start time
        val hit = sim.eventStartingIn(first.tOffsetMs - 1, first.tOffsetMs + 1)
        assertTrue(hit != null)
        // a window before any event starts
        val firstStart = events.minOf { it.tOffsetMs }
        val miss = sim.eventStartingIn(0, firstStart)
        assertEquals(null, miss)
    }
}
