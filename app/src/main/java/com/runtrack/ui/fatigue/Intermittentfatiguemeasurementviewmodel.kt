package com.runtrack.ui.fatigue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.data.db.FatigueDao
import com.runtrack.data.db.FatigueSession
import com.runtrack.data.db.MdfPoint
import com.runtrack.domain.RunDataSource
import com.runtrack.domain.analysis.FatigueAnalyzer
import com.runtrack.domain.analysis.FatigueAnalyzer.WindowFeature
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.EmgSide
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 간헐 수축(Intermittent Contraction) 방식 근전도 측정 상태머신.
 *
 * 프로토콜 (총 60초):
 *   0~2초    : 준비 안내
 *   2~7초    : 1회차 수축 (5초)
 *   7~12초   : 휴식 (5초)
 *   12~17초  : 2회차 수축
 *   17~22초  : 휴식
 *   22~27초  : 3회차 수축
 *   27~32초  : 휴식
 *   32~37초  : 4회차 수축
 *   37~42초  : 휴식
 *   42~47초  : 5회차 수축
 *   47~52초  : 휴식
 *   52~57초  : 6회차 수축
 *   57~60초  : 마무리 (3초)
 *
 * 총 시간 = 2 + 5×6 + 5×5 + 3 = 60초
 *
 * 테스트 단계이므로 좌/우 한쪽 모듈만 연결되어도 측정 가능.
 */
@HiltViewModel
class IntermittentFatigueMeasurementViewModel @Inject constructor(
    private val source: RunDataSource,
    private val dao: FatigueDao,
) : ViewModel() {

    companion object {
        const val PREPARE_SEC = 2
        const val CONTRACT_SEC = 5          // 각 수축 시간
        const val REST_SEC = 5              // 각 휴식 시간
        const val COOLDOWN_SEC = 3          // 마무리
        const val CONTRACTION_COUNT = 6     // 총 수축 횟수

        /** 총 60초 = 2 + 5×6 + 5×5 + 3 */
        const val TOTAL_SEC = PREPARE_SEC +
                CONTRACT_SEC * CONTRACTION_COUNT +
                REST_SEC * (CONTRACTION_COUNT - 1) +
                COOLDOWN_SEC

        // 5초 수축이면 데이터가 훨씬 많이 쌓이므로 최소 유효 개수 상향
        const val MIN_VALID_WINDOWS_PER_CONTRACTION = 5
    }

    enum class Phase { PRE, POST }

    enum class Stage { PREPARE, CONTRACT, REST, COOLDOWN }

    sealed interface State {
        data object Idle : State
        data class Running(
            val phase: Phase,
            val stage: Stage,
            val contractionIndex: Int,
            val secondsLeftInStage: Int,
            val totalElapsedSec: Int,
            val liveLeft: List<WindowFeature>,      // 분석용 (CONTRACT 구간만)
            val liveRight: List<WindowFeature>,
            val previewLeft: List<Float>,           // 시각화용 (전 구간 RMS, 최근 60개)
            val previewRight: List<Float>,
        ) : State
        data class PreDone(
            val sessionId: Long,
            val hasLeft: Boolean,
            val hasRight: Boolean,
        ) : State
        data class Complete(
            val sessionId: Long,
            val fiLeftPercent: Double?,
            val fiRightPercent: Double?,
            val rmsChangeLeftPercent: Double?,
            val rmsChangeRightPercent: Double?,
        ) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _pendingSessionId = MutableStateFlow<Long?>(null)
    val pendingSessionId: StateFlow<Long?> = _pendingSessionId.asStateFlow()

    /** 좌/우 근전도 모듈의 실시간 연결 상태 */
    val emgConnectionStates: StateFlow<Map<EmgSide, ConnectionState>> =
        source.emgConnectionStates

    private var job: Job? = null

    init {
        viewModelScope.launch {
            dao.sessions().collect { list ->
                _pendingSessionId.value =
                    list.firstOrNull { it.postMeasuredAtMs == null }?.id
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            source.disconnect()
            source.scanAndConnect()
        }
    }

    fun startPre() = runMeasurement(Phase.PRE)

    fun startPost() {
        if (_pendingSessionId.value == null) {
            _state.value = State.Error("운동 전 측정 기록이 없습니다. 운동 전 측정부터 해주세요.")
            return
        }
        runMeasurement(Phase.POST)
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    /** 특정 시각(초)에서 어떤 stage 인지, 몇 번째 수축인지, stage 내 남은 시간은? */
    private fun stageAt(elapsedSec: Int): Triple<Stage, Int, Int> {
        if (elapsedSec < PREPARE_SEC) {
            return Triple(Stage.PREPARE, 0, PREPARE_SEC - elapsedSec)
        }
        val afterPrepare = elapsedSec - PREPARE_SEC
        val cyclesTotalSec = CONTRACT_SEC * CONTRACTION_COUNT + REST_SEC * (CONTRACTION_COUNT - 1)
        if (afterPrepare < cyclesTotalSec) {
            var t = afterPrepare
            var contractionIdx = 1
            while (contractionIdx <= CONTRACTION_COUNT) {
                if (t < CONTRACT_SEC) {
                    return Triple(Stage.CONTRACT, contractionIdx, CONTRACT_SEC - t)
                }
                t -= CONTRACT_SEC
                if (contractionIdx < CONTRACTION_COUNT) {
                    if (t < REST_SEC) {
                        return Triple(Stage.REST, contractionIdx, REST_SEC - t)
                    }
                    t -= REST_SEC
                }
                contractionIdx++
            }
        }
        val cooldownRemaining = TOTAL_SEC - elapsedSec
        return Triple(Stage.COOLDOWN, CONTRACTION_COUNT, cooldownRemaining.coerceAtLeast(0))
    }

    private fun runMeasurement(phase: Phase) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val startMs = System.currentTimeMillis()
                val endMs = startMs + TOTAL_SEC * 1000L

                val leftByContraction = mutableMapOf<Int, MutableList<WindowFeature>>()
                val rightByContraction = mutableMapOf<Int, MutableList<WindowFeature>>()

                // 시각화 전용 링 버퍼 (stage 무관하게 계속 흐름)
                val previewMax = 60
                val previewLeft = ArrayDeque<Float>()
                val previewRight = ArrayDeque<Float>()

                source.emgFeatures.collect { sf ->
                    val now = System.currentTimeMillis()
                    val elapsedSec = ((now - startMs) / 1000L).toInt().coerceAtLeast(0)

                    if (now >= endMs) {
                        finish(phase, leftByContraction, rightByContraction)
                        job?.cancel()
                        return@collect
                    }

                    val (stage, contractionIdx, secLeft) = stageAt(elapsedSec)

                    // 시각화 buffer: stage 무관하게 항상 append
                    when (sf.side) {
                        EmgSide.LEFT_THIGH -> {
                            previewLeft.addLast(sf.feature.rms.toFloat())
                            while (previewLeft.size > previewMax) previewLeft.removeFirst()
                        }
                        EmgSide.RIGHT_THIGH -> {
                            previewRight.addLast(sf.feature.rms.toFloat())
                            while (previewRight.size > previewMax) previewRight.removeFirst()
                        }
                    }

                    // 분석 buffer: CONTRACT 구간에서만 append (기존 로직 유지)
                    if (stage == Stage.CONTRACT) {
                        when (sf.side) {
                            EmgSide.LEFT_THIGH ->
                                leftByContraction.getOrPut(contractionIdx) { mutableListOf() } += sf.feature
                            EmgSide.RIGHT_THIGH ->
                                rightByContraction.getOrPut(contractionIdx) { mutableListOf() } += sf.feature
                        }
                    }

                    _state.value = State.Running(
                        phase = phase,
                        stage = stage,
                        contractionIndex = contractionIdx,
                        secondsLeftInStage = secLeft,
                        totalElapsedSec = elapsedSec,
                        liveLeft = leftByContraction.values.flatten(),
                        liveRight = rightByContraction.values.flatten(),
                        previewLeft = previewLeft.toList(),
                        previewRight = previewRight.toList(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "측정 오류")
            }
        }
    }

    private suspend fun finish(
        phase: Phase,
        leftByContraction: Map<Int, List<WindowFeature>>,
        rightByContraction: Map<Int, List<WindowFeature>>,
    ) {
        val validLeft = leftByContraction.filterValues { it.size >= MIN_VALID_WINDOWS_PER_CONTRACTION }
        val validRight = rightByContraction.filterValues { it.size >= MIN_VALID_WINDOWS_PER_CONTRACTION }

        // 한쪽이라도 절반(3회) 이상 유효 수축이면 진행
        val leftOk = validLeft.size >= CONTRACTION_COUNT / 2
        val rightOk = validRight.size >= CONTRACTION_COUNT / 2

        if (!leftOk && !rightOk) {
            _state.value = State.Error(
                "유효 수축 부족 (좌 ${validLeft.size}/$CONTRACTION_COUNT · 우 ${validRight.size}/$CONTRACTION_COUNT)\n" +
                        "화면 안내에 맞춰 힘을 정확히 주었는지 확인하고 다시 시도해주세요."
            )
            return
        }

        // 유효한 쪽만 수축별 요약 후 6회를 다시 요약
        val leftPer = if (leftOk) {
            validLeft.entries.sortedBy { it.key }.map { (_, feats) ->
                WindowFeature(
                    timeMs = feats.first().timeMs,
                    mdfHz = feats.map { it.mdfHz }.average(),
                    rms = feats.maxOfOrNull { it.rms } ?: 0.0
                )
            }
        } else null

        val rightPer = if (rightOk) {
            validRight.entries.sortedBy { it.key }.map { (_, feats) ->
                WindowFeature(
                    timeMs = feats.first().timeMs,
                    mdfHz = feats.map { it.mdfHz }.average(),
                    rms = feats.maxOfOrNull { it.rms } ?: 0.0
                )
            }
        } else null

        val sumL = leftPer?.let { FatigueAnalyzer.summarize(it) }
        val sumR = rightPer?.let { FatigueAnalyzer.summarize(it) }

        when (phase) {
            Phase.PRE -> {
                val id = dao.insertSession(
                    FatigueSession(
                        startedAtMs = System.currentTimeMillis(),
                        preMdfLeftHz = sumL?.mdfMeanHz ?: 0.0,
                        preMdfRightHz = sumR?.mdfMeanHz ?: 0.0,
                        preRmsLeft = sumL?.rmsMean ?: 0.0,
                        preRmsRight = sumR?.rmsMean ?: 0.0,
                        preWindowCount = maxOf(sumL?.windowCount ?: 0, sumR?.windowCount ?: 0),
                    )
                )
                savePoints(id, "PRE", leftPer ?: emptyList(), rightPer ?: emptyList())
                _state.value = State.PreDone(id, hasLeft = leftOk, hasRight = rightOk)
            }
            Phase.POST -> {
                val id = _pendingSessionId.value ?: run {
                    _state.value = State.Error("운동 전 세션이 없습니다"); return
                }
                val s = dao.session(id) ?: run {
                    _state.value = State.Error("세션 조회 실패"); return
                }

                val resL = if (sumL != null && s.preMdfLeftHz > 0.0) {
                    FatigueAnalyzer.compare(
                        FatigueAnalyzer.MeasurementSummary(s.preMdfLeftHz, 0.0, s.preRmsLeft, s.preWindowCount),
                        sumL
                    )
                } else null

                val resR = if (sumR != null && s.preMdfRightHz > 0.0) {
                    FatigueAnalyzer.compare(
                        FatigueAnalyzer.MeasurementSummary(s.preMdfRightHz, 0.0, s.preRmsRight, s.preWindowCount),
                        sumR
                    )
                } else null

                dao.updateSession(
                    s.copy(
                        postMdfLeftHz = sumL?.mdfMeanHz,
                        postMdfRightHz = sumR?.mdfMeanHz,
                        postRmsLeft = sumL?.rmsMean,
                        postRmsRight = sumR?.rmsMean,
                        postWindowCount = maxOf(sumL?.windowCount ?: 0, sumR?.windowCount ?: 0),
                        postMeasuredAtMs = System.currentTimeMillis(),
                        fiLeftPercent = resL?.fatigueIndexPercent,
                        fiRightPercent = resR?.fatigueIndexPercent,
                    )
                )
                savePoints(id, "POST", leftPer ?: emptyList(), rightPer ?: emptyList())
                _state.value = State.Complete(
                    sessionId = id,
                    fiLeftPercent = resL?.fatigueIndexPercent,
                    fiRightPercent = resR?.fatigueIndexPercent,
                    rmsChangeLeftPercent = resL?.rmsChangePercent,
                    rmsChangeRightPercent = resR?.rmsChangePercent,
                )
            }
        }
    }

    private suspend fun savePoints(
        sessionId: Long, phase: String,
        left: List<WindowFeature>, right: List<WindowFeature>,
    ) {
        when {
            left.isNotEmpty() && right.isNotEmpty() -> {
                val n = minOf(left.size, right.size)
                dao.insertPoints(
                    (0 until n).map { i ->
                        MdfPoint(
                            sessionId = sessionId, phase = phase,
                            timeMs = left[i].timeMs,
                            mdfLeftHz = left[i].mdfHz,
                            mdfRightHz = right[i].mdfHz,
                        )
                    }
                )
            }
            left.isNotEmpty() -> {
                dao.insertPoints(
                    left.map { f ->
                        MdfPoint(
                            sessionId = sessionId, phase = phase,
                            timeMs = f.timeMs,
                            mdfLeftHz = f.mdfHz,
                            mdfRightHz = 0.0,
                        )
                    }
                )
            }
            right.isNotEmpty() -> {
                dao.insertPoints(
                    right.map { f ->
                        MdfPoint(
                            sessionId = sessionId, phase = phase,
                            timeMs = f.timeMs,
                            mdfLeftHz = 0.0,
                            mdfRightHz = f.mdfHz,
                        )
                    }
                )
            }
        }
    }
}