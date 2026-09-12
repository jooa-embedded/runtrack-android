package com.runtrack.domain.model

/* ─────────────────────────  Enums  ───────────────────────── */

enum class JointLocation(val displayName: String, val isKnee: Boolean, val isLeft: Boolean) {
    LEFT_ANKLE("왼쪽 발목", isKnee = false, isLeft = true),
    RIGHT_ANKLE("오른쪽 발목", isKnee = false, isLeft = false),
    LEFT_KNEE("왼쪽 무릎", isKnee = true, isLeft = true),
    RIGHT_KNEE("오른쪽 무릎", isKnee = true, isLeft = false);

    companion object {
        val ankles = listOf(LEFT_ANKLE, RIGHT_ANKLE)
        val knees = listOf(LEFT_KNEE, RIGHT_KNEE)
    }
}

enum class EmgLocation(val displayName: String) {
    THIGH("허벅지")
}

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }

enum class EventSeverity(val displayName: String) {
    LOW("경미"), MEDIUM("주의"), HIGH("심각")
}

enum class FormBreakType(val displayName: String) {
    IMPACT_SPIKE("충격량 급증"),
    ATTENUATION_DROP("충격 흡수 저하"),
    ASYMMETRY("좌우 불균형"),
    CADENCE_IRREGULAR("케이던스 불안정")
}

enum class InsightSentiment { POSITIVE, NEUTRAL, WARNING }

/* ────────────────────  Real-time data  ──────────────────── */

/** A single processed frame the hub sends ~5-10 Hz. Already computed values, not raw signals. */
data class TelemetryFrame(
    val tOffsetMs: Long,
    val pta: Map<JointLocation, Float>,
    val loadingRate: Map<JointLocation, Float>,
    val shockAttenuationLeft: Float,
    val shockAttenuationRight: Float,
    val medianFreqHz: Float,           // 좌/우 평균 (기존 호환용)
    val meanFreqHz: Float,
    val rms: Float,                    // 좌/우 평균 (기존 호환용)
    // ── 좌/우 각각의 EMG 값 (2대 EMG 모듈 지원) ──
    val rmsLeft: Float? = null,
    val rmsRight: Float? = null,
    val medianFreqHzLeft: Float? = null,
    val medianFreqHzRight: Float? = null,
    val signalQuality: Float,
    val cadenceSpm: Int,
    val trunkPitchDeg: Float? = null,
    val trunkRollDeg: Float? = null,
    // 알고리즘 1 지표 (HubAlgorithmSummaryPacket에서 수신, 없으면 null)
    val impactScore: Float? = null,
    val postureScore: Float? = null,
    val impactLevel: Int? = null,
    val postureCollapseHint: Int? = null,
    val algorithmCadenceSpm: Float? = null,
    val dynamicRmsG: Float? = null
)

/** Status of one of the 5 sensor nodes (4 IMU + 1 EMG). */
data class NodeStatus(
    val nodeId: String,
    val label: String,
    val batteryPercent: Int,
    val isOnline: Boolean
)

/* ──────────────────  Persisted run models  ─────────────────── */

data class RunSession(
    val sessionId: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Int,
    val userWeightKgSnapshot: Float,
    val distanceMeters: Float?,
    val title: String,
    val note: String,
    val createdAt: Long
)

data class ImpactSample(
    val tOffsetMs: Long,
    val location: JointLocation,
    val ptaAvgG: Float,
    val ptaMaxG: Float,
    val loadingRateAvgGPerS: Float,
    val cumulativeImpulse: Float
)

data class EmgSample(
    val tOffsetMs: Long,
    val medianFreqHz: Float,
    val meanFreqHz: Float,
    val rms: Float,
    val signalQuality: Float
)

data class FormBreakEvent(
    val tOffsetMs: Long,
    val type: FormBreakType,
    val severity: EventSeverity,
    val affectedJoint: JointLocation?,
    val description: String,
    val durationMs: Long,
    val id: Long = 0
)

/** Per-joint aggregate for one session. */
data class LocationSummary(
    val location: JointLocation,
    val ptaAvgG: Float,
    val ptaMaxG: Float,
    val ptaStdG: Float,
    val avgAttenuation: Float
)

/** Pre-computed session summary, stored for fast comparison. */
data class RunSummary(
    val cumulativeImpulseTotal: Float,
    val asymmetryIndex: Float,      // % difference between left and right impact load
    val mdfStartHz: Float,
    val mdfEndHz: Float,
    val fatigueIndexPct: Float,     // how much MDF dropped from start (%)
    val avgRms: Float,
    val formBreakCount: Int,
    val avgCadenceSpm: Int,
    val locationSummaries: List<LocationSummary>,
    /** true면 왼쪽이 더 큼, false면 오른쪽이 더 큼, null이면 계산 불가 (한쪽 숨김/데이터 부족). */
    val asymmetryLeftHeavier: Boolean? = null
) {
    fun summaryFor(location: JointLocation): LocationSummary? =
        locationSummaries.firstOrNull { it.location == location }

    val avgKneePta: Float
        get() = locationSummaries.filter { it.location.isKnee }
            .map { it.ptaAvgG }.average().toFloat().orZero()

    val avgAnklePta: Float
        get() = locationSummaries.filter { !it.location.isKnee }
            .map { it.ptaAvgG }.average().toFloat().orZero()

    val avgKneeAttenuation: Float
        get() = locationSummaries.filter { it.location.isKnee }
            .map { it.avgAttenuation }.average().toFloat().orZero()
}

private fun Float.orZero() = if (isNaN()) 0f else this

/* ──────────────────  Aggregates for screens  ─────────────────── */

/** Everything the run-detail screen needs, loaded in one shot. */
data class RunDetail(
    val session: RunSession,
    val summary: RunSummary,
    val impactSamples: List<ImpactSample>,       // 숨김 제외
    val emgSamples: List<EmgSample>,
    val events: List<FormBreakEvent>,            // 숨김 제외
    val hiddenLocations: List<JointLocation> = emptyList(),   // ← 추가
    val hiddenEvents: List<FormBreakEvent> = emptyList()      // ← 추가
)

/** Session + summary pair, used by history and comparison screens. */
data class RunWithSummary(
    val session: RunSession,
    val summary: RunSummary
)

/** A single comparison feedback line produced by the analysis engine. */
data class Insight(
    val sentiment: InsightSentiment,
    val title: String,
    val message: String,
    val metricName: String,
    val deltaText: String
)
