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
 * 운동 전/후 근전도 측정 상태머신 (월싯 방식).
 * 안정화 5초(폐기) → 측정 25초 → 좌/우 중 최소 한쪽이라도 데이터 있으면 저장/비교.
 * (테스트 단계이므로 단일 모듈로도 측정 가능)
 */
@HiltViewModel
class FatigueMeasurementViewModel @Inject constructor(
    private val source: RunDataSource,
    private val dao: FatigueDao,
) : ViewModel() {

    companion object {
        const val STABILIZE_SEC = 5
        const val MEASURE_SEC = 25
        const val MIN_VALID_WINDOWS = 15
    }

    enum class Phase { PRE, POST }

    sealed interface State {
        data object Idle : State
        data class Stabilizing(val phase: Phase, val secondsLeft: Int) : State
        data class Measuring(
            val phase: Phase,
            val secondsLeft: Int,
            val liveLeft: List<WindowFeature>,
            val liveRight: List<WindowFeature>,
        ) : State
        data class PreDone(
            val sessionId: Long,
            val hasLeft: Boolean,
            val hasRight: Boolean,
        ) : State
        data class Complete(
            val sessionId: Long,
            val fiLeftPercent: Double?,      // 좌 모듈 없으면 null
            val fiRightPercent: Double?,     // 우 모듈 없으면 null
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

    private fun runMeasurement(phase: Phase) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val stabilizeUntil = System.currentTimeMillis() + STABILIZE_SEC * 1000L
                val measureUntil = stabilizeUntil + MEASURE_SEC * 1000L
                val left = mutableListOf<WindowFeature>()
                val right = mutableListOf<WindowFeature>()

                source.emgFeatures.collect { sf ->
                    val now = sf.feature.timeMs
                    when {
                        now < stabilizeUntil -> {
                            _state.value = State.Stabilizing(
                                phase, (((stabilizeUntil - now) / 1000L).toInt() + 1)
                            )
                        }
                        now < measureUntil -> {
                            when (sf.side) {
                                EmgSide.LEFT_THIGH -> left += sf.feature
                                EmgSide.RIGHT_THIGH -> right += sf.feature
                            }
                            _state.value = State.Measuring(
                                phase, (((measureUntil - now) / 1000L).toInt() + 1),
                                liveLeft = left.toList(),
                                liveRight = right.toList(),
                            )
                        }
                        else -> {
                            finish(phase, left, right)
                            job?.cancel()
                        }
                    }
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
        left: List<WindowFeature>,
        right: List<WindowFeature>,
    ) {
        // 한쪽이라도 유효 데이터 있으면 진행
        val leftValid = left.size >= MIN_VALID_WINDOWS
        val rightValid = right.size >= MIN_VALID_WINDOWS

        if (!leftValid && !rightValid) {
            _state.value = State.Error(
                "유효 데이터 부족 (좌 ${left.size} / 우 ${right.size}, 최소 각 $MIN_VALID_WINDOWS)\n" +
                        "전극 부착과 보드 연결 상태를 확인한 뒤 다시 측정해주세요."
            )
            return
        }

        // 유효한 쪽만 요약
        val sumL = if (leftValid) FatigueAnalyzer.summarize(left) else null
        val sumR = if (rightValid) FatigueAnalyzer.summarize(right) else null

        when (phase) {
            Phase.PRE -> {
                // 없는 쪽은 0.0으로 저장 (POST에서 매칭시 활용 안 함)
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
                savePoints(id, "PRE", left, right)
                _state.value = State.PreDone(
                    sessionId = id,
                    hasLeft = leftValid,
                    hasRight = rightValid,
                )
            }
            Phase.POST -> {
                val id = _pendingSessionId.value ?: run {
                    _state.value = State.Error("운동 전 세션이 없습니다"); return
                }
                val s = dao.session(id) ?: run {
                    _state.value = State.Error("세션 조회 실패"); return
                }

                // 좌/우 각각 PRE에 값 있고 POST에도 값 있을 때만 비교
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
                savePoints(id, "POST", left, right)
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
        // 좌/우 중 있는 쪽만 저장 (양쪽 다 있으면 짝지어서, 한쪽만 있으면 그쪽만)
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