package com.runtrack.ui.liverun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.RunDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.runtrack.domain.RunRepository
import com.runtrack.domain.UserPreferencesRepository
import com.runtrack.domain.analysis.RunDataAggregator
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.RunSession
import com.runtrack.domain.model.TelemetryFrame
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI snapshot for the live run. Telemetry arrives at ~5 Hz; the chart history
 * is downsampled so the UI never has to render thousands of points.
 */
data class LiveRunUiState(
    val elapsedSec: Int = 0,
    val latestFrame: TelemetryFrame? = null,
    val kneeHistory: List<Float> = emptyList(),
    val ankleHistory: List<Float> = emptyList(),
    val activeAlert: FormBreakEvent? = null,
    val saving: Boolean = false,
    val savedSessionId: Long? = null,
    // Baseline (초반 3분 자동 수집)
    val baselineReady: Boolean = false,
    val baselineMdf: Float = 0f,
    val baselineRms: Float = 0f,
    val mdfChangePercent: Float? = null,
    val rmsChangePercent: Float? = null,
    // 누적 충격량 (Impulse, 단위: g·s)
    val totalImpulse: Float = 0f
) {
    fun pta(joint: JointLocation): Float = latestFrame?.pta?.get(joint) ?: 0f
    val attenuationLeft: Float get() = latestFrame?.shockAttenuationLeft ?: 0f
    val attenuationRight: Float get() = latestFrame?.shockAttenuationRight ?: 0f
    val medianFreqHz: Float get() = latestFrame?.medianFreqHz ?: 0f
    val rms: Float get() = latestFrame?.rms ?: 0f
    val cadenceSpm: Int get() = latestFrame?.cadenceSpm ?: 0
    val signalQuality: Float get() = latestFrame?.signalQuality ?: 0f
    val trunkPitchDeg: Float? get() = latestFrame?.trunkPitchDeg
    val trunkRollDeg: Float? get() = latestFrame?.trunkRollDeg
    // 좌/우 EMG 값 각각 (null이면 해당 모듈 미연결)
    val rmsLeft: Float? get() = latestFrame?.rmsLeft
    val rmsRight: Float? get() = latestFrame?.rmsRight
    val medianFreqHzLeft: Float? get() = latestFrame?.medianFreqHzLeft
    val medianFreqHzRight: Float? get() = latestFrame?.medianFreqHzRight
    val impactScore: Float? get() = latestFrame?.impactScore
    val postureScore: Float? get() = latestFrame?.postureScore
    val impactLevel: Int? get() = latestFrame?.impactLevel
    val postureCollapseHint: Int? get() = latestFrame?.postureCollapseHint
    val algorithmCadenceSpm: Float? get() = latestFrame?.algorithmCadenceSpm
    val dynamicRmsG: Float? get() = latestFrame?.dynamicRmsG
}

@HiltViewModel
class LiveRunViewModel @Inject constructor(
    private val dataSource: RunDataSource,
    private val repository: RunRepository,
    private val aggregator: RunDataAggregator,
    private val userPreferences: UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LiveRunUiState())
    val state: StateFlow<LiveRunUiState> = _state.asStateFlow()

    // full-resolution buffers, kept for the final aggregation/save
    private val frameBuffer = mutableListOf<TelemetryFrame>()
    private val eventBuffer = mutableListOf<FormBreakEvent>()

    // ── Baseline 수집용 버퍼 ──
    private val baselineMdfBuffer = mutableListOf<Float>()
    private val baselineRmsBuffer = mutableListOf<Float>()
    private var baselineCompleted = false
    private var computedBaselineMdf = 0f
    private var computedBaselineRms = 0f

    // 누적 충격량 (모든 관절 impulse 합계, 단위: g·s)
    private var accumulatedImpulse = 0f
    private var lastFrameTimeMs: Long = 0L
    private val startedAt = System.currentTimeMillis()

    init {
        // collect telemetry; throttle UI updates to ~2 Hz
        viewModelScope.launch {
            var lastUiUpdateMs = 0L
            dataSource.liveTelemetry.collect { frame ->
                frameBuffer += frame
                if (frame.tOffsetMs - lastUiUpdateMs >= UI_THROTTLE_MS) {
                    lastUiUpdateMs = frame.tOffsetMs
                    pushUi(frame)
                }
            }
        }
        // collect form-break events
        viewModelScope.launch {
            dataSource.formBreakEvents.collect { event ->
                eventBuffer += event
                _state.update { it.copy(activeAlert = event) }
            }
        }
    }

    private fun pushUi(frame: TelemetryFrame) {
        val kneeAvg = JointLocation.knees.map { frame.pta[it] ?: 0f }.average().toFloat()
        val ankleAvg = JointLocation.ankles.map { frame.pta[it] ?: 0f }.average().toFloat()
        val elapsedSec = (frame.tOffsetMs / 1000L).toInt()

        // ── 누적 충격량 (Impulse) 계산 ──
        // 프레임 간격(초) × 4관절 peak_g 합계
        val dtSec = if (lastFrameTimeMs > 0L) {
            (frame.tOffsetMs - lastFrameTimeMs) / 1000f
        } else {
            0.2f  // 첫 프레임은 5Hz 가정
        }
        val totalPeakG = (frame.pta[JointLocation.LEFT_ANKLE] ?: 0f) +
                (frame.pta[JointLocation.RIGHT_ANKLE] ?: 0f) +
                (frame.pta[JointLocation.LEFT_KNEE] ?: 0f) +
                (frame.pta[JointLocation.RIGHT_KNEE] ?: 0f)
        accumulatedImpulse += totalPeakG * dtSec
        lastFrameTimeMs = frame.tOffsetMs

        // ── Baseline 수집 (30초~600초 구간, 자동) ──
        if (!baselineCompleted && elapsedSec in BASELINE_START_SEC..BASELINE_END_SEC) {
            if (frame.medianFreqHz > 0f) {
                baselineMdfBuffer.add(frame.medianFreqHz)
                baselineRmsBuffer.add(frame.rms)
            }
        }
        // 10분(600초) 지나면 Baseline 확정
        if (!baselineCompleted && elapsedSec > BASELINE_END_SEC && baselineMdfBuffer.size >= BASELINE_MIN_SAMPLES) {
            computedBaselineMdf = baselineMdfBuffer.average().toFloat()
            computedBaselineRms = baselineRmsBuffer.average().toFloat()
            baselineCompleted = true
        }

        // ── Baseline 대비 변화율 계산 ──
        val mdfChange = if (baselineCompleted && computedBaselineMdf > 0f && frame.medianFreqHz > 0f) {
            ((frame.medianFreqHz - computedBaselineMdf) / computedBaselineMdf * 100f)
        } else null

        val rmsChange = if (baselineCompleted && computedBaselineRms > 0f && frame.rms > 0f) {
            ((frame.rms - computedBaselineRms) / computedBaselineRms * 100f)
        } else null

        _state.update { s ->
            s.copy(
                elapsedSec = elapsedSec,
                latestFrame = frame,
                kneeHistory = (s.kneeHistory + kneeAvg).takeLast(HISTORY_POINTS),
                ankleHistory = (s.ankleHistory + ankleAvg).takeLast(HISTORY_POINTS),
                baselineReady = baselineCompleted,
                baselineMdf = computedBaselineMdf,
                baselineRms = computedBaselineRms,
                mdfChangePercent = mdfChange,
                rmsChangePercent = rmsChange,
                totalImpulse = accumulatedImpulse
            )
        }
    }

    /** Stops the session, aggregates the buffered data and persists the run. */
    fun stopAndSave() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            dataSource.stopSession()

            // 무거운 집계 작업을 백그라운드 스레드에서 실행 (UI 블락 방지)
            val (session, aggregated, events) = withContext(Dispatchers.Default) {
                val frames = frameBuffer.toList()
                val eventList = eventBuffer.toList()
                val agg = aggregator.aggregate(frames, eventList)

                val endedAt = System.currentTimeMillis()
                val durationSec = ((endedAt - startedAt) / 1000L).toInt().coerceAtLeast(1)
                val weight = userPreferences.weightKg.first().takeIf { it > 0f } ?: 65f

                val s = RunSession(
                    sessionId = 0,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    durationSec = durationSec,
                    userWeightKgSnapshot = weight,
                    distanceMeters = null,
                    title = defaultTitle(),
                    note = "",
                    createdAt = endedAt
                )
                Triple(s, agg, eventList)
            }

            // DB 저장 (Repository 내부에서 이미 Dispatchers.IO 사용)
            val id = repository.saveCompletedRun(
                session = session,
                impactSamples = aggregated.impactSamples,
                emgSamples = aggregated.emgSamples,
                events = events,
                summary = aggregated.summary
            )
            dataSource.disconnect()
            _state.update { it.copy(saving = false, savedSessionId = id) }
        }
    }

    /** Throws away a too-short run without saving. */
    fun discard(onDone: () -> Unit) {
        viewModelScope.launch {
            dataSource.stopSession()
            dataSource.disconnect()
            onDone()
        }
    }

    private fun defaultTitle(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "아침 러닝"
            in 11..16 -> "낮 러닝"
            in 17..20 -> "저녁 러닝"
            else -> "야간 러닝"
        }
    }

    override fun onCleared() {
        super.onCleared()
        // safety: if the VM dies mid-run, stop the mock stream
        viewModelScope.launch { dataSource.stopSession() }
    }

    private companion object {
        const val UI_THROTTLE_MS = 1000L      // update UI ~2 Hz
        const val HISTORY_POINTS = 30        // rolling chart window
        const val BASELINE_START_SEC = 30     // 수집 시작 (초)
        const val BASELINE_END_SEC = 600      // 수집 종료 (3분)
        const val BASELINE_MIN_SAMPLES = 30   // 최소 샘플 수
    }
}
