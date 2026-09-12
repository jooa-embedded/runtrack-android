package com.runtrack.domain.analysis

import com.runtrack.domain.model.EmgSample
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.ImpactSample
import com.runtrack.domain.model.Insight
import com.runtrack.domain.model.InsightSentiment
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.LocationSummary
import com.runtrack.domain.model.RunSummary
import com.runtrack.domain.model.TelemetryFrame
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Output of [RunDataAggregator]. */
data class AggregatedRun(
    val impactSamples: List<ImpactSample>,
    val emgSamples: List<EmgSample>,
    val summary: RunSummary
)

/**
 * Turns a raw stream of [TelemetryFrame]s into 1 Hz samples + a [RunSummary].
 * Used both by the live run (after a session) and by seed-data generation,
 * so the two paths always produce identical-shaped data.
 */
class RunDataAggregator @Inject constructor() {

    fun aggregate(
        frames: List<TelemetryFrame>,
        events: List<FormBreakEvent>
    ): AggregatedRun {
        if (frames.isEmpty()) {
            return AggregatedRun(emptyList(), emptyList(), emptySummary())
        }

        val bySecond = frames.groupBy { (it.tOffsetMs / 1000L) }.toSortedMap()

        // ── per-location 1 Hz impact samples + running cumulative impulse ──
        val cumulative = JointLocation.entries.associateWith { 0f }.toMutableMap()
        val impactSamples = mutableListOf<ImpactSample>()
        val emgSamples = mutableListOf<EmgSample>()

        for ((second, group) in bySecond) {
            val tOffsetMs = second * 1000L
            for (joint in JointLocation.entries) {
                val ptas = group.map { it.pta[joint] ?: 0f }
                val avg = ptas.average().toFloat()
                val max = ptas.max()
                val lr = group.map { it.loadingRate[joint] ?: 0f }.average().toFloat()
                cumulative[joint] = (cumulative[joint] ?: 0f) + avg // 1 s integration step
                impactSamples += ImpactSample(
                    tOffsetMs = tOffsetMs,
                    location = joint,
                    ptaAvgG = avg,
                    ptaMaxG = max,
                    loadingRateAvgGPerS = lr,
                    cumulativeImpulse = cumulative[joint] ?: 0f
                )
            }
            emgSamples += EmgSample(
                tOffsetMs = tOffsetMs,
                medianFreqHz = group.map { it.medianFreqHz }.average().toFloat(),
                meanFreqHz = group.map { it.meanFreqHz }.average().toFloat(),
                rms = group.map { it.rms }.average().toFloat(),
                signalQuality = group.map { it.signalQuality }.average().toFloat()
            )
        }

        // ── per-location summary ──
        val locationSummaries = JointLocation.entries.map { joint ->
            val ptas = frames.map { it.pta[joint] ?: 0f }
            val mean = ptas.average().toFloat()
            val variance = ptas.map { (it - mean) * (it - mean) }.average().toFloat()
            val attenuation = when (joint) {
                JointLocation.LEFT_KNEE ->
                    frames.map { it.shockAttenuationLeft }.average().toFloat()
                JointLocation.RIGHT_KNEE ->
                    frames.map { it.shockAttenuationRight }.average().toFloat()
                else -> 0f
            }
            LocationSummary(
                location = joint,
                ptaAvgG = mean,
                ptaMaxG = ptas.max(),
                ptaStdG = sqrt(variance),
                avgAttenuation = attenuation
            )
        }

        // ── fatigue (MDF start vs end) ──
        val window = (frames.size / 20).coerceAtLeast(1) // ~first/last 5 %
        val mdfStart = frames.take(window).map { it.medianFreqHz }.average().toFloat()
        val mdfEnd = frames.takeLast(window).map { it.medianFreqHz }.average().toFloat()
        val fatiguePct = if (mdfStart > 0f)
            ((mdfStart - mdfEnd) / mdfStart * 100f).coerceAtLeast(0f) else 0f

        // ── asymmetry (left vs right ankle load) ──
        val leftAnkle = locationSummaries.first { it.location == JointLocation.LEFT_ANKLE }.ptaAvgG
        val rightAnkle = locationSummaries.first { it.location == JointLocation.RIGHT_ANKLE }.ptaAvgG
        val asymmetry = if (leftAnkle + rightAnkle > 0f)
            abs(leftAnkle - rightAnkle) / ((leftAnkle + rightAnkle) / 2f) * 100f else 0f
        val leftHeavier = if (leftAnkle + rightAnkle > 0f) leftAnkle > rightAnkle else null  // ← 추가

        val summary = RunSummary(
            cumulativeImpulseTotal = cumulative.values.sum(),
            asymmetryIndex = asymmetry,
            mdfStartHz = mdfStart,
            mdfEndHz = mdfEnd,
            fatigueIndexPct = fatiguePct,
            avgRms = frames.map { it.rms }.average().toFloat(),
            formBreakCount = events.size,
            avgCadenceSpm = frames.map { it.cadenceSpm }.average().roundToInt(),
            locationSummaries = locationSummaries,
            asymmetryLeftHeavier = leftHeavier
        )
        return AggregatedRun(impactSamples, emgSamples, summary)
    }

    private fun emptySummary() = RunSummary(
        cumulativeImpulseTotal = 0f,
        asymmetryIndex = 0f,
        mdfStartHz = 0f,
        mdfEndHz = 0f,
        fatigueIndexPct = 0f,
        avgRms = 0f,
        formBreakCount = 0,
        avgCadenceSpm = 0,
        locationSummaries = JointLocation.entries.map {
            LocationSummary(it, 0f, 0f, 0f, 0f)
        }
    )
}

/**
 * Rule-based comparison engine. Compares the current run to a baseline
 * (typically the previous run) and produces human-readable Korean feedback.
 * All thresholds live here so they are easy to tune.
 */
class RunComparisonEngine @Inject constructor() {

    private object Th {
        const val PTA_CHANGE = 5f          // % change worth reporting
        const val ATTENUATION_CHANGE = 4f  // %
        const val FATIGUE_CHANGE = 5f      // percentage points
        const val ASYMMETRY_WARN = 8f      // % asymmetry considered notable
    }

    fun compare(current: RunSummary, baseline: RunSummary?): List<Insight> {
        if (baseline == null) return firstRunInsights(current)

        val insights = mutableListOf<Insight>()

        // 1. Knee impact
        pctChange(baseline.avgKneePta, current.avgKneePta)?.let { change ->
            if (abs(change) >= Th.PTA_CHANGE) {
                insights += if (change < 0) Insight(
                    sentiment = InsightSentiment.POSITIVE,
                    title = "무릎 충격 감소",
                    message = "무릎에 가해진 평균 충격이 ${fmt(-change)}% 줄었어요. " +
                            "착지 자세가 개선된 것으로 보입니다.",
                    metricName = "무릎 평균 충격",
                    deltaText = "${fmt(change)}%"
                ) else Insight(
                    sentiment = InsightSentiment.WARNING,
                    title = "무릎 충격 증가",
                    message = "무릎 충격이 ${fmt(change)}% 늘었어요. " +
                            "피로 누적이나 신발 쿠션 상태를 점검해 보세요.",
                    metricName = "무릎 평균 충격",
                    deltaText = "+${fmt(change)}%"
                )
            }
        }

        // 2. Shock attenuation (knee absorbing the impact)
        pctChange(baseline.avgKneeAttenuation, current.avgKneeAttenuation)?.let { change ->
            if (abs(change) >= Th.ATTENUATION_CHANGE) {
                insights += if (change > 0) Insight(
                    sentiment = InsightSentiment.POSITIVE,
                    title = "충격 흡수 향상",
                    message = "무릎이 충격을 더 잘 흡수하고 있어요(+${fmt(change)}%). " +
                            "관절 부담이 줄어드는 좋은 신호입니다.",
                    metricName = "충격 흡수율",
                    deltaText = "+${fmt(change)}%"
                ) else Insight(
                    sentiment = InsightSentiment.WARNING,
                    title = "충격 흡수 저하",
                    message = "무릎의 충격 흡수가 ${fmt(-change)}% 떨어졌어요. " +
                            "후반부 자세가 무너지지 않았는지 확인해 보세요.",
                    metricName = "충격 흡수율",
                    deltaText = "${fmt(change)}%"
                )
            }
        }

        // 3. Fatigue
        val fatigueDelta = current.fatigueIndexPct - baseline.fatigueIndexPct
        if (abs(fatigueDelta) >= Th.FATIGUE_CHANGE) {
            insights += if (fatigueDelta > 0) Insight(
                sentiment = InsightSentiment.WARNING,
                title = "근피로 증가",
                message = "이번엔 근육이 더 빨리 피로해졌어요. " +
                        "회복이 부족했거나 강도가 높았을 수 있습니다.",
                metricName = "피로 지수",
                deltaText = "+${fmt(fatigueDelta)}p"
            ) else Insight(
                sentiment = InsightSentiment.POSITIVE,
                title = "근지구력 향상",
                message = "근육 피로가 ${fmt(-fatigueDelta)}p 줄었어요. " +
                        "지구력이 좋아지고 있습니다.",
                metricName = "피로 지수",
                deltaText = "${fmt(fatigueDelta)}p"
            )
        }

        // 4. Asymmetry
        if (current.asymmetryIndex >= Th.ASYMMETRY_WARN) {
            insights += Insight(
                sentiment = InsightSentiment.WARNING,
                title = "좌우 불균형",
                message = "좌우 충격 차이가 ${fmt(current.asymmetryIndex)}%로 큽니다. " +
                        "한쪽 다리에 부담이 쏠릴 수 있어요.",
                metricName = "좌우 비대칭",
                deltaText = "${fmt(current.asymmetryIndex)}%"
            )
        }

        // 5. Form-break count
        val eventDelta = current.formBreakCount - baseline.formBreakCount
        if (eventDelta != 0) {
            insights += if (eventDelta < 0) Insight(
                sentiment = InsightSentiment.POSITIVE,
                title = "자세 안정",
                message = "자세가 무너진 순간이 ${-eventDelta}회 줄었어요. 더 안정적으로 달렸습니다.",
                metricName = "자세 무너짐",
                deltaText = "$eventDelta 회"
            ) else Insight(
                sentiment = InsightSentiment.WARNING,
                title = "자세 무너짐 증가",
                message = "자세가 무너진 순간이 ${eventDelta}회 늘었어요. " +
                        "타임라인에서 어느 구간이었는지 확인해 보세요.",
                metricName = "자세 무너짐",
                deltaText = "+$eventDelta 회"
            )
        }

        if (insights.isEmpty()) {
            insights += Insight(
                sentiment = InsightSentiment.NEUTRAL,
                title = "지난번과 비슷해요",
                message = "주요 지표가 직전 러닝과 큰 차이가 없습니다. 일관된 페이스를 유지했어요.",
                metricName = "종합",
                deltaText = "—"
            )
        }
        return insights
    }

    private fun firstRunInsights(current: RunSummary) = listOf(
        Insight(
            sentiment = InsightSentiment.NEUTRAL,
            title = "첫 러닝 기록 완료",
            message = "비교할 이전 기록이 아직 없어요. 다음 러닝부터 변화를 분석해 드릴게요.",
            metricName = "종합",
            deltaText = "—"
        ),
        Insight(
            sentiment = if (current.asymmetryIndex >= Th.ASYMMETRY_WARN)
                InsightSentiment.WARNING else InsightSentiment.NEUTRAL,
            title = "기준점 기록",
            message = "무릎 평균 충격 ${fmt(current.avgKneePta)}g, " +
                    "피로 지수 ${fmt(current.fatigueIndexPct)}%를 기준으로 잡았습니다.",
            metricName = "기준",
            deltaText = "—"
        )
    )

    private fun pctChange(from: Float, to: Float): Float? =
        if (from <= 0f) null else (to - from) / from * 100f

    private fun fmt(v: Float): String = "%.1f".format(abs(v))
}
