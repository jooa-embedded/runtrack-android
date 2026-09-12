package com.runtrack.domain

import com.runtrack.domain.analysis.SidedEmgFeature
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.EmgFeatureSample
import com.runtrack.domain.model.EmgSide
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.NodeStatus
import com.runtrack.domain.model.RunPhase
import com.runtrack.domain.model.TelemetryFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between the app and the sensor hub.
 *
 * 기기 구성:
 *   RunHub-ESP32S3 …… 허브 (발목/무릎/허리 IMU 노드)
 *   MyoWare-EMG   …… 오른쪽 허벅지 근전도 (EmgSide.RIGHT_THIGH)
 *   MyoWare-EMG-2 …… 왼쪽 허벅지 근전도  (EmgSide.LEFT_THIGH)
 *
 * 근전도 모듈 2대는 Service/TX/RX UUID가 동일하고 Advertising Name으로만 구분한다.
 * 따라서 연결 상태와 수신 데이터는 항상 [EmgSide]를 키로 다룬다.
 */
interface RunDataSource {

    /** Hub connection lifecycle. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * 근전도 모듈 2대를 합친 대표 상태.
     *  - 둘 다 연결   → CONNECTED
     *  - 하나만 연결  → CONNECTED (부분 연결)
     *  - 하나라도 진행 중 → CONNECTING
     * 세부 상태가 필요하면 [emgConnectionStates]를 쓸 것.
     */
    val emgConnectionState: StateFlow<ConnectionState>

    /** 좌/우 근전도 모듈 각각의 연결 상태. */
    val emgConnectionStates: StateFlow<Map<EmgSide, ConnectionState>>

    /** Battery / online status of sensor nodes (허브 노드 + 근전도 2대). */
    val nodeStatus: StateFlow<List<NodeStatus>>

    /** Processed telemetry stream while a session is running (~5-10 Hz). */
    val liveTelemetry: SharedFlow<TelemetryFrame>

    /** Form-break alerts, emitted irregularly. */
    val formBreakEvents: SharedFlow<FormBreakEvent>

    /** side/phase가 붙은 근전도 원시 샘플 스트림 (저장·그래프용). */
    val emgSamples: SharedFlow<EmgFeatureSample>

    /**
     * 근전도 측정 화면(FatigueMeasureScreen)용 특징값 스트림.
     * 러닝 중이 아니어도 EMG 모듈이 연결되어 있으면 계속 흘러야 함.
     */
    val emgFeatures: SharedFlow<SidedEmgFeature>

    /** 현재 측정 구간. emgSamples의 phase 컬럼에 반영된다. */
    val phase: StateFlow<RunPhase>

    suspend fun scanAndConnect()

    /** 아직 붙지 않은 근전도 모듈만 다시 검색/연결한다. */
    suspend fun reconnectEmgOnly()

    suspend fun startSession()
    suspend fun stopSession()
    suspend fun disconnect()

    /** 측정 구간 전환 (before_run → during_run → after_run). */
    fun setPhase(phase: RunPhase)
}