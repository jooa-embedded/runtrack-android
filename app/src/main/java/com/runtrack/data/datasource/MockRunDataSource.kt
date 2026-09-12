package com.runtrack.data.datasource

import com.runtrack.data.local.EmgCsvLogger
import com.runtrack.domain.RunDataSource
import com.runtrack.domain.analysis.FatigueAnalyzer
import com.runtrack.domain.analysis.SidedEmgFeature
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.EmgFeatureSample
import com.runtrack.domain.model.EmgSide
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.NodeStatus
import com.runtrack.domain.model.RunPhase
import com.runtrack.domain.model.TelemetryFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.runtrack.domain.model.JointLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random

/**
 * Phase 1 stand-in for the real BLE hub. 하드웨어 없이 전체 앱이 동작하도록
 * 허브 텔레메트리 + 근전도 모듈 2대(좌/우) 데이터를 모두 흉내 낸다.
 *
 * 실기기 전환은 AppModule의 @Binds 한 줄만 [BleRunDataSource]로 바꾸면 된다.
 * 근전도 CSV 적재 경로도 동일하게 태우므로 에뮬레이터에서 저장 파이프라인까지 검증 가능.
 */
@Singleton
class MockRunDataSource @Inject constructor(
    private val csvLogger: EmgCsvLogger
) : RunDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    private val _emgConnectionStates = MutableStateFlow(
        EmgSide.entries.associateWith { ConnectionState.DISCONNECTED }
    )
    override val emgConnectionStates: StateFlow<Map<EmgSide, ConnectionState>> =
        _emgConnectionStates.asStateFlow()

    private val _emgConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val emgConnectionState = _emgConnectionState.asStateFlow()

    private val _nodeStatus = MutableStateFlow<List<NodeStatus>>(emptyList())
    override val nodeStatus = _nodeStatus.asStateFlow()

    private val _liveTelemetry = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 64)
    override val liveTelemetry = _liveTelemetry.asSharedFlow()

    private val _formBreakEvents = MutableSharedFlow<FormBreakEvent>(extraBufferCapacity = 16)
    override val formBreakEvents = _formBreakEvents.asSharedFlow()

    private val _emgSamples = MutableSharedFlow<EmgFeatureSample>(extraBufferCapacity = 256)
    override val emgSamples: SharedFlow<EmgFeatureSample> = _emgSamples.asSharedFlow()

    // 근전도 측정 화면(FatigueMeasureScreen)용 스트림 — BleRunDataSource와 동일 인터페이스
    private val _emgFeatures = MutableSharedFlow<SidedEmgFeature>(extraBufferCapacity = 256)
    override val emgFeatures: SharedFlow<SidedEmgFeature> = _emgFeatures.asSharedFlow()

    private val _phase = MutableStateFlow(RunPhase.BEFORE_RUN)
    override val phase: StateFlow<RunPhase> = _phase.asStateFlow()

    private var sessionJob: Job? = null
    private var emgJob: Job? = null

    // Fatigue 측정을 위해 근전도만 항상 흘려보내는 잡 (러닝 중이든 아니든)
    private var standaloneEmgJob: Job? = null

    private var standaloneTelemetryJob: Job? = null

    // ────────────────────────────────────────────────────

    override suspend fun scanAndConnect() {
        _connectionState.value = ConnectionState.SCANNING
        EmgSide.entries.forEach { setEmgState(it, ConnectionState.SCANNING) }
        delay(1200)

        _connectionState.value = ConnectionState.CONNECTING
        EmgSide.entries.forEach { setEmgState(it, ConnectionState.CONNECTING) }
        delay(900)

        _connectionState.value = ConnectionState.CONNECTED
        // 실제 환경처럼 좌/우가 시차를 두고 붙는 상황을 재현
        EmgSide.entries.forEachIndexed { i, side ->
            delay(400L * i)
            setEmgState(side, ConnectionState.CONNECTED)
        }
        refreshNodes()

        // 근전도 측정 화면 (Fatigue)에서도 값이 필요하므로 연결되면 항상 스트림 흘리기
        startStandaloneEmgStream()
        // 실기기 허브가 연결만 되어도 텔레메트리를 계속 보내는 것을 흉내낸다.
        startStandaloneTelemetryStream()
    }

    override suspend fun reconnectEmgOnly() {
        EmgSide.entries.forEach {
            if (_emgConnectionStates.value[it] != ConnectionState.CONNECTED) {
                setEmgState(it, ConnectionState.SCANNING)
            }
        }
        delay(1500)
        EmgSide.entries.forEach { setEmgState(it, ConnectionState.CONNECTED) }
        refreshNodes()
        startStandaloneEmgStream()
        startStandaloneTelemetryStream()
    }

    /** 러닝 중이 아니어도 EMG 신호를 흘려서 Fatigue 측정 화면이 동작하도록 함 */
    private fun startStandaloneEmgStream() {
        if (standaloneEmgJob?.isActive == true) return
        standaloneEmgJob = scope.launch {
            val periodMs = 1000L / EMG_EMIT_HZ
            val startReal = System.currentTimeMillis()
            var seq = 0
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startReal
                seq++
                EmgSide.entries.forEach { side ->
                    if (_emgConnectionStates.value[side] != ConnectionState.CONNECTED) return@forEach
                    emitEmgSignals(side, seq, elapsed)
                }
                delay(periodMs)
            }
        }
    }

    /** 러닝 시작 전에도 IMU 값이 흐르는 것을 흉내낸다 (기기 연결 화면의 실시간 프리뷰용). */
    private fun startStandaloneTelemetryStream() {
        if (standaloneTelemetryJob?.isActive == true) return
        standaloneTelemetryJob = scope.launch {
            val periodMs = 1000L / 5   // 5Hz면 눈으로 보기 충분
            while (isActive) {
                // "서 있는/걷는" 정도의 작은 값. 러닝은 startSession의 simulator가 담당.
                val jitter = { base: Float, spread: Float ->
                    base + (Random.nextFloat() - 0.5f) * spread
                }
                val frame = TelemetryFrame(
                    tOffsetMs = 0L,
                    pta = mapOf(
                        JointLocation.LEFT_ANKLE  to jitter(0.35f, 0.15f),
                        JointLocation.RIGHT_ANKLE to jitter(0.35f, 0.15f),
                        JointLocation.LEFT_KNEE   to jitter(0.20f, 0.10f),
                        JointLocation.RIGHT_KNEE  to jitter(0.20f, 0.10f),
                    ),
                    loadingRate = JointLocation.entries.associateWith { 0f },
                    shockAttenuationLeft  = 0.5f,
                    shockAttenuationRight = 0.5f,
                    medianFreqHz = 0f,
                    meanFreqHz   = 0f,
                    rms          = 0f,
                    signalQuality = 1f,
                    cadenceSpm   = 0,                      // 대기 중이므로 0
                    trunkPitchDeg = jitter(2.0f, 1.5f),
                    trunkRollDeg  = jitter(0.0f, 1.0f),
                )
                _liveTelemetry.emit(frame)
                delay(periodMs)
            }
        }
    }

    override suspend fun startSession() {
        sessionJob?.cancel()
        emgJob?.cancel()
        csvLogger.start()

        val simulator = RunSimulator(
            seed = System.currentTimeMillis(),
            fitness = 0.55f + Random.nextFloat() * 0.25f,
            runDurationSec = FATIGUE_HORIZON_SEC
        )
        val startReal = System.currentTimeMillis()

        sessionJob = scope.launch {
            val periodMs = 1000L / EMIT_HZ
            var lastElapsed = -1L
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startReal
                _liveTelemetry.emit(simulator.frameAt(elapsed))
                simulator.eventStartingIn(lastElapsed + 1, elapsed + 1)
                    ?.let { _formBreakEvents.emit(it) }
                lastElapsed = elapsed
                delay(periodMs)
            }
        }
        // 러닝 중에는 standalone EMG는 계속 돌아가고 있으므로 별도 emgJob은 생략
    }

    override suspend fun stopSession() {
        sessionJob?.cancel(); sessionJob = null
        emgJob?.cancel(); emgJob = null
        csvLogger.stop()
    }

    override fun setPhase(phase: RunPhase) {
        _phase.value = phase
    }

    override suspend fun disconnect() {
        sessionJob?.cancel(); sessionJob = null
        emgJob?.cancel(); emgJob = null
        standaloneEmgJob?.cancel(); standaloneEmgJob = null
        standaloneTelemetryJob?.cancel(); standaloneTelemetryJob = null
        csvLogger.stop()
        _nodeStatus.value = emptyList()
        _connectionState.value = ConnectionState.DISCONNECTED
        EmgSide.entries.forEach { setEmgState(it, ConnectionState.DISCONNECTED) }
    }

    // ────────────────────────────────────────────────────

    /**
     * 좌/우 EMG 신호를 만들어서 _emgSamples 와 _emgFeatures 양쪽에 emit.
     * 피로가 쌓이면 RMS는 오르고 median frequency는 내려가는 경향을 흉내 냄.
     */
    private suspend fun emitEmgSignals(side: EmgSide, seq: Int, elapsedMs: Long) {
        val fatigue = (elapsedMs / 1000f / FATIGUE_HORIZON_SEC).coerceIn(0f, 1f)
        val asym = if (side == EmgSide.RIGHT_THIGH) 1.04f else 0.96f  // 좌우 비대칭
        val rms = (16f + fatigue * 9f) * asym + Random.nextFloat() * 1.5f
        val mdf = max(28f, (48f - fatigue * 12f) * asym) + Random.nextFloat()

        val sample = EmgFeatureSample(
            deviceName     = side.deviceName,
            address        = if (side == EmgSide.RIGHT_THIGH) "AA:BB:CC:00:00:01"
            else "AA:BB:CC:00:00:02",
            side           = side,
            phase          = _phase.value,
            sequence       = seq,
            timeMs         = elapsedMs,
            receivedAtMs   = System.currentTimeMillis(),
            rms            = rms,
            mav            = rms * 0.81f,
            waveformLength = rms * 72f + Random.nextFloat() * 20f,
            meanFreq       = mdf * 1.29f,
            medianFreq     = mdf
        )
        _emgSamples.emit(sample)
        csvLogger.append(sample)

        // 근전도 측정 화면(FatigueMeasureScreen)용 스트림
        _emgFeatures.tryEmit(
            SidedEmgFeature(
                side = side,
                feature = FatigueAnalyzer.WindowFeature(
                    timeMs = System.currentTimeMillis(),
                    mdfHz = mdf.toDouble(),
                    rms = rms.toDouble()
                )
            )
        )
    }

    private fun setEmgState(side: EmgSide, state: ConnectionState) {
        _emgConnectionStates.update { it + (side to state) }
        val all = _emgConnectionStates.value.values
        _emgConnectionState.value = when {
            all.all { it == ConnectionState.CONNECTED }   -> ConnectionState.CONNECTED
            all.any { it == ConnectionState.CONNECTING ||
                    it == ConnectionState.SCANNING }    -> ConnectionState.CONNECTING
            all.any { it == ConnectionState.CONNECTED }   -> ConnectionState.CONNECTED
            all.any { it == ConnectionState.ERROR }       -> ConnectionState.ERROR
            else                                          -> ConnectionState.DISCONNECTED
        }
        refreshNodes()
    }

    /** 실기기와 동일하게 허브 노드 5개 + 근전도 2개를 만든다. */
    private fun refreshNodes() {
        if (_connectionState.value != ConnectionState.CONNECTED &&
            _emgConnectionStates.value.values.none { it == ConnectionState.CONNECTED }
        ) return

        val hubNodes = HUB_NODE_LABELS.mapIndexed { i, label ->
            NodeStatus(
                nodeId = "HUB-NODE-${i + 1}",
                label = label,
                batteryPercent = 62 + Random.nextInt(38),
                isOnline = _connectionState.value == ConnectionState.CONNECTED
            )
        }
        val emgNodes = EmgSide.entries.map { side ->
            NodeStatus(
                nodeId = "EMG-${side.storageKey}",
                label = "${side.label} (근전도)",
                batteryPercent = 62 + Random.nextInt(38),
                isOnline = _emgConnectionStates.value[side] == ConnectionState.CONNECTED
            )
        }
        _nodeStatus.value = hubNodes + emgNodes
    }

    private companion object {
        const val EMIT_HZ = 5L
        const val EMG_EMIT_HZ = 10L
        const val FATIGUE_HORIZON_SEC = 1500   // fatigue model reaches full at 25 min
        val HUB_NODE_LABELS = listOf(
            "왼쪽 발목", "오른쪽 발목", "왼쪽 무릎", "오른쪽 무릎", "허리"
        )
    }
}