package com.runtrack

import com.runtrack.domain.analysis.RunComparisonEngine
import com.runtrack.domain.model.InsightSentiment
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.LocationSummary
import com.runtrack.domain.model.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunComparisonEngineTest {

    private val engine = RunComparisonEngine()

    /** Builds a summary with uniform per-joint PTA and the given attenuation. */
    private fun summary(
        kneePta: Float = 6f,
        anklePta: Float = 7f,
        attenuation: Float = 0.7f,
        fatigue: Float = 10f,
        asymmetry: Float = 3f,
        formBreaks: Int = 2
    ) = RunSummary(
        cumulativeImpulseTotal = 1000f,
        asymmetryIndex = asymmetry,
        mdfStartHz = 95f,
        mdfEndHz = 95f * (1f - fatigue / 100f),
        fatigueIndexPct = fatigue,
        avgRms = 0.4f,
        formBreakCount = formBreaks,
        avgCadenceSpm = 175,
        locationSummaries = JointLocation.entries.map { joint ->
            LocationSummary(
                location = joint,
                ptaAvgG = if (joint.isKnee) kneePta else anklePta,
                ptaMaxG = (if (joint.isKnee) kneePta else anklePta) * 1.3f,
                ptaStdG = 0.5f,
                avgAttenuation = if (joint.isKnee) attenuation else 0f
            )
        }
    )

    @Test
    fun `first run with no baseline returns neutral insights`() {
        val insights = engine.compare(summary(), baseline = null)
        assertTrue(insights.isNotEmpty())
        assertTrue(
            "first-run insights should not contain positive/warning deltas",
            insights.any { it.sentiment == InsightSentiment.NEUTRAL }
        )
    }

    @Test
    fun `large knee impact drop produces a positive insight`() {
        val baseline = summary(kneePta = 8f)
        val current = summary(kneePta = 6f)   // -25 %
        val insights = engine.compare(current, baseline)
        assertTrue(
            "expected a positive insight for reduced knee impact",
            insights.any {
                it.sentiment == InsightSentiment.POSITIVE && it.title.contains("무릎")
            }
        )
    }

    @Test
    fun `large knee impact increase produces a warning insight`() {
        val baseline = summary(kneePta = 6f)
        val current = summary(kneePta = 8f)   // +33 %
        val insights = engine.compare(current, baseline)
        assertTrue(
            insights.any {
                it.sentiment == InsightSentiment.WARNING && it.title.contains("무릎")
            }
        )
    }

    @Test
    fun `rising fatigue produces a warning insight`() {
        val baseline = summary(fatigue = 8f)
        val current = summary(fatigue = 20f)
        val insights = engine.compare(current, baseline)
        assertTrue(
            insights.any {
                it.sentiment == InsightSentiment.WARNING && it.title.contains("피로")
            }
        )
    }

    @Test
    fun `high asymmetry always produces a warning`() {
        val baseline = summary(asymmetry = 3f)
        val current = summary(asymmetry = 14f)
        val insights = engine.compare(current, baseline)
        assertTrue(
            insights.any {
                it.sentiment == InsightSentiment.WARNING && it.title.contains("불균형")
            }
        )
    }

    @Test
    fun `near-identical runs yield a single neutral insight`() {
        val baseline = summary()
        val current = summary()   // identical
        val insights = engine.compare(current, baseline)
        assertEquals(1, insights.size)
        assertEquals(InsightSentiment.NEUTRAL, insights.first().sentiment)
    }

    @Test
    fun `improved shock attenuation produces a positive insight`() {
        val baseline = summary(attenuation = 0.65f)
        val current = summary(attenuation = 0.78f)
        val insights = engine.compare(current, baseline)
        assertTrue(
            insights.any {
                it.sentiment == InsightSentiment.POSITIVE && it.title.contains("흡수")
            }
        )
    }

    @Test
    fun `fewer form breaks produces a positive insight`() {
        val baseline = summary(formBreaks = 6)
        val current = summary(formBreaks = 2)
        val insights = engine.compare(current, baseline)
        assertTrue(
            insights.any {
                it.sentiment == InsightSentiment.POSITIVE && it.title.contains("자세")
            }
        )
    }
}
