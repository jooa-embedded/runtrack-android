package com.runtrack.data.datasource

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.runtrack.data.local.EmgCsvLogger
import com.runtrack.domain.RunDataSource
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.EmgFeatureSample
import com.runtrack.domain.model.EmgSide
import com.runtrack.domain.model.EventSeverity
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.FormBreakType
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.NodeStatus
import com.runtrack.domain.model.RunPhase
import com.runtrack.domain.model.TelemetryFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 허브(RunHub-ESP32S3) 1대 + 근전도 모듈(MyoWare-EMG / MyoWare-EMG-2) 2대 동시 BLE 데이터 소스.
 *
 * ── 기기 구성 ──
 *   RunHub-ESP32S3 : 발목/무릎/허리 노드 요약 패킷 (46-byte binary)
 *   MyoWare-EMG    : 오른쪽 허벅지 EMG (NUS CSV 텍스트)
 *   MyoWare-EMG-2  : 왼쪽 허벅지 EMG   (NUS CSV 텍스트, UUID는 위와 동일)
 *
 * ── 연결 전략 ──
 * 1) 필터 없이 스캔하고 Advertising Name '완전 일치'로 3대를 식별한다.
 *    (startsWith 쓰면 "MyoWare-EMG-2"가 "MyoWare-EMG"에도 걸려 좌우가 뒤집힌다)
 * 2) connectGatt은 절대 동시에 부르지 않는다. Channel 기반 직렬 큐로 한 대씩,
 *    이전 기기의 서비스 탐색이 끝난 뒤 다음 기기를 연결한다. (status 133 방지)
 * 3) status != GATT_SUCCESS로 끊기면 지수 백오프로 최대 3회 자동 재시도한다.
 *
 * ── 데이터 ──
 * EMG 패킷 수신 시 side/phase를 붙여 EmgFeatureSample로 만들고
 *   - emgSamples SharedFlow로 방출 (UI/Repository용)
 *   - EmgCsvLogger로 CSV 적재
 * 허브 프레임에는 좌우 EMG 평균값을 머지한다.
 */
@Singleton
class BleRunDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val csvLogger: EmgCsvLogger
) : RunDataSource {

    private val tag = "BleRunDataSource"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── 상태 flows ──
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState = _connectionState.asStateFlow()

    /** 좌/우 EMG 모듈 각각의 연결 상태 */
    private val _emgConnectionStates = MutableStateFlow(
        EmgSide.entries.associateWith { ConnectionState.DISCONNECTED }
    )
    override val emgConnectionStates: StateFlow<Map<EmgSide, ConnectionState>> =
        _emgConnectionStates.asStateFlow()

    /** 두 모듈을 합친 대표 상태 (기존 UI 호환용) */
    private val _emgConnectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val emgConnectionState = _emgConnectionState.asStateFlow()

    private val _nodeStatus = MutableStateFlow<List<NodeStatus>>(emptyList())
    override val nodeStatus = _nodeStatus.asStateFlow()

    private val _liveTelemetry = MutableSharedFlow<TelemetryFrame>(extraBufferCapacity = 64)
    override val liveTelemetry = _liveTelemetry.asSharedFlow()

    private val _formBreakEvents = MutableSharedFlow<FormBreakEvent>(extraBufferCapacity = 16)
    override val formBreakEvents = _formBreakEvents.asSharedFlow()

    /** 좌/우 구분이 붙은 EMG 원시 샘플 스트림 */
    private val _emgSamples = MutableSharedFlow<EmgFeatureSample>(extraBufferCapacity = 256)
    override val emgSamples: SharedFlow<EmgFeatureSample> = _emgSamples.asSharedFlow()

    // 근전도 측정 화면용 특징값 스트림 (좌/우)
    private val _emgFeatures = kotlinx.coroutines.flow.MutableSharedFlow<com.runtrack.domain.analysis.SidedEmgFeature>(extraBufferCapacity = 256)
    override val emgFeatures: kotlinx.coroutines.flow.SharedFlow<com.runtrack.domain.analysis.SidedEmgFeature> = _emgFeatures

    /** 현재 측정 구간 (before_run / during_run / after_run) */
    private val _phase = MutableStateFlow(RunPhase.BEFORE_RUN)
    override val phase: StateFlow<RunPhase> = _phase.asStateFlow()

    // ── BLE 기본 객체 ──
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // ── 허브 링크 ──
    private var hubGatt: BluetoothGatt? = null
    private var hubDevice: BluetoothDevice? = null
    private var hubQueued = false
    private var hubRetry = 0
    private var hubReady: CompletableDeferred<Boolean>? = null

    // ── EMG 링크 (좌/우) ──
    private class EmgLink(val side: EmgSide) {
        var device: BluetoothDevice? = null
        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var queued: Boolean = false
        var retry: Int = 0
        var ready: CompletableDeferred<Boolean>? = null
        val rxBuffer = StringBuilder()

        @Volatile
        var latest: EmgBleProtocol.EmgFeature? = null
    }

    private val emgLinks: Map<EmgSide, EmgLink> =
        EmgSide.entries.associateWith { EmgLink(it) }

    private val emgCallbacks: Map<EmgSide, BluetoothGattCallback> =
        EmgSide.entries.associateWith { makeEmgCallback(it) }

    // ── 직렬 연결 큐 ──
    private class ConnectRequest(val device: BluetoothDevice, val side: EmgSide?) // side==null → 허브
    private val connectQueue = Channel<ConnectRequest>(Channel.UNLIMITED)

    // ── 세션 상태 ──
    private var sessionStartMs: Long = 0L
    private var lastWarningFlags: Int = 0
    private var lastHubOnlineMask: Int = 0
    private var scanning = false

    init {
        // 연결 요청을 한 건씩 순차 처리하는 워커
        scope.launch {
            for (req in connectQueue) {
                try {
                    connectOne(req)
                } catch (e: Exception) {
                    Log.e(tag, "connect worker error", e)
                }
                delay(500)  // 다음 연결 전 BT 스택 안정화
            }
        }
    }

    // ────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override suspend fun scanAndConnect() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(tag, "Bluetooth disabled or unsupported")
            _connectionState.value = ConnectionState.ERROR
            EmgSide.entries.forEach { setEmgState(it, ConnectionState.ERROR) }
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            _connectionState.value = ConnectionState.ERROR
            EmgSide.entries.forEach { setEmgState(it, ConnectionState.ERROR) }
            return
        }

        if (scanning) {
            Log.d(tag, "Scan already running")
            return
        }

        if (hubGatt == null) _connectionState.value = ConnectionState.SCANNING
        EmgSide.entries.forEach {
            if (emgLinks.getValue(it).gatt == null) setEmgState(it, ConnectionState.SCANNING)
        }
        Log.d(tag, "Scanning for hub + ${EmgBleProtocol.DEVICE_NAMES}")

        // ScanFilter.setDeviceName()은 완전 일치라, 광고 이름이 잘리면(31byte 초과)
        // 아예 안 잡힌다. 3대뿐이므로 필터 없이 스캔하고 코드에서 판별한다.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        scanning = true

        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (!scanning) return@launch
            stopScanSafely()
            if (hubGatt == null && !hubQueued) {
                Log.w(tag, "Scan timeout - hub not found")
                _connectionState.value = ConnectionState.ERROR
            }
            EmgSide.entries.forEach { side ->
                val link = emgLinks.getValue(side)
                if (link.gatt == null && !link.queued) {
                    Log.w(tag, "Scan timeout - ${side.deviceName} not found")
                    setEmgState(side, ConnectionState.ERROR)
                }
            }
        }
    }

    /** 아직 못 붙은 EMG 모듈만 다시 스캔/연결 (UI의 '근전도 재연결' 버튼용) */
    @SuppressLint("MissingPermission")
    override suspend fun reconnectEmgOnly() {
        EmgSide.entries.forEach { side ->
            val link = emgLinks.getValue(side)
            if (link.gatt == null) {
                link.retry = 0
                link.queued = false
            }
        }
        scanAndConnect()
    }

    override suspend fun startSession() {
        sessionStartMs = System.currentTimeMillis()
        lastWarningFlags = 0
        emgLinks.values.forEach { it.latest = null; it.rxBuffer.setLength(0) }
        csvLogger.start()
        Log.d(tag, "Session started, sessionStartMs=$sessionStartMs, csv=${csvLogger.currentFile}")
        sendEmgCommand("START")   // 두 모듈 모두에게
    }

    override suspend fun stopSession() {
        sessionStartMs = 0L
        sendEmgCommand("STOP")
        csvLogger.stop()
        Log.d(tag, "Session stopped")
    }

    /** 측정 구간 전환. 이후 수신되는 샘플의 phase 컬럼에 반영된다. */
    override fun setPhase(phase: RunPhase) {
        _phase.value = phase
        Log.d(tag, "Phase → ${phase.key}")
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        Log.d(tag, "Disconnect requested")
        stopScanSafely()

        hubGatt?.let { runCatching { it.disconnect(); it.close() } }
        hubGatt = null
        hubDevice = null
        hubQueued = false
        hubRetry = 0
        _connectionState.value = ConnectionState.DISCONNECTED

        emgLinks.values.forEach { link ->
            link.gatt?.let { runCatching { it.disconnect(); it.close() } }
            link.gatt = null
            link.rx = null
            link.device = null
            link.queued = false
            link.retry = 0
            link.latest = null
            link.rxBuffer.setLength(0)
            setEmgState(link.side, ConnectionState.DISCONNECTED)
        }

        csvLogger.stop()
        _nodeStatus.value = emptyList()
    }

    /**
     * EMG 모듈로 텍스트 명령 전송. side를 지정하지 않으면 연결된 모든 모듈에 보낸다.
     * 예: "START", "STOP", "STATUS"
     */
    @SuppressLint("MissingPermission")
    fun sendEmgCommand(command: String, side: EmgSide? = null) {
        val targets = if (side == null) emgLinks.values else listOf(emgLinks.getValue(side))
        targets.forEach { link ->
            val gatt = link.gatt ?: return@forEach
            val rx = link.rx ?: return@forEach
            val bytes = command.toByteArray(Charsets.UTF_8)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = bytes
                    @Suppress("DEPRECATION")
                    rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(rx)
                }
            }.onFailure { Log.e(tag, "[${link.side.deviceName}] write failed", it) }
            Log.d(tag, "[${link.side.deviceName}] command sent: $command")
        }
    }

    // ────────────────────────────────────────────────────
    //  스캔
    // ────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // scanRecord 쪽 이름을 우선 사용 (device.name은 캐시된 옛 이름일 수 있음)
            val name = result.scanRecord?.deviceName ?: device.name ?: return

            if (name == BleProtocol.HUB_DEVICE_NAME) {
                if (hubGatt == null && !hubQueued) {
                    Log.d(tag, "Found HUB $name ${device.address}")
                    hubDevice = device
                    hubQueued = true
                    _connectionState.value = ConnectionState.CONNECTING
                    connectQueue.trySend(ConnectRequest(device, null))
                }
            } else {
                // ★ 완전 일치로 좌/우 판별 (prefix 비교 금지)
                val side = EmgSide.fromScanResult(name, device.address) ?: return
                val link = emgLinks.getValue(side)
                if (link.gatt == null && !link.queued) {
                    Log.d(tag, "Found EMG ${side.label} / $name ${device.address}")
                    link.device = device
                    link.queued = true
                    setEmgState(side, ConnectionState.CONNECTING)
                    connectQueue.trySend(ConnectRequest(device, side))
                }
            }

            if (hubQueued && emgLinks.values.all { it.queued }) stopScanSafely()
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            // errorCode 2 = APPLICATION_REGISTRATION_FAILED,
            // 6 = SCANNING_TOO_FREQUENTLY (30초 내 5회 초과 시작)
            Log.e(tag, "Scan failed: $errorCode")
            scanning = false
            if (hubGatt == null) _connectionState.value = ConnectionState.ERROR
            EmgSide.entries.forEach {
                if (emgLinks.getValue(it).gatt == null) setEmgState(it, ConnectionState.ERROR)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafely() {
        if (!scanning) return
        scanning = false
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ────────────────────────────────────────────────────
    //  직렬 연결 워커
    // ────────────────────────────────────────────────────

    /** 한 기기를 연결하고 서비스 탐색이 끝날 때까지 기다린다. 다음 기기는 그 뒤에 연결. */
    @SuppressLint("MissingPermission")
    private suspend fun connectOne(req: ConnectRequest) {
        val ready = CompletableDeferred<Boolean>()
        val label = req.side?.deviceName ?: BleProtocol.HUB_DEVICE_NAME

        if (req.side == null) hubReady = ready else emgLinks.getValue(req.side).ready = ready

        withContext(Dispatchers.Main) {
            Log.d(tag, "Connecting $label ${req.device.address}")
            // TRANSPORT_LE 미지정 시 일부 기기에서 BR/EDR을 먼저 시도하다 status 133 발생
            val gatt = req.device.connectGatt(
                context, false,
                if (req.side == null) hubGattCallback else emgCallbacks.getValue(req.side),
                BluetoothDevice.TRANSPORT_LE
            )
            if (req.side == null) hubGatt = gatt else emgLinks.getValue(req.side).gatt = gatt
        }

        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() } ?: false
        Log.d(tag, "Connect finished: $label ok=$ok")
    }

    private fun scheduleRetry(side: EmgSide?, status: Int) {
        if (side == null) {
            val dev = hubDevice
            if (status != BluetoothGatt.GATT_SUCCESS && dev != null && hubRetry < MAX_RETRY) {
                hubRetry++
                Log.w(tag, "HUB connect failed(status=$status) → retry $hubRetry")
                _connectionState.value = ConnectionState.CONNECTING
                scope.launch {
                    delay(RETRY_BASE_MS * hubRetry)
                    connectQueue.send(ConnectRequest(dev, null))
                }
            } else {
                hubQueued = false
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            return
        }

        val link = emgLinks.getValue(side)
        val dev = link.device
        if (status != BluetoothGatt.GATT_SUCCESS && dev != null && link.retry < MAX_RETRY) {
            link.retry++
            Log.w(tag, "[${side.deviceName}] connect failed(status=$status) → retry ${link.retry}")
            setEmgState(side, ConnectionState.CONNECTING)
            scope.launch {
                delay(RETRY_BASE_MS * link.retry)
                connectQueue.send(ConnectRequest(dev, side))
            }
        } else {
            link.queued = false
            setEmgState(side, ConnectionState.DISCONNECTED)
        }
    }

    // ────────────────────────────────────────────────────
    //  허브 GATT
    // ────────────────────────────────────────────────────

    private val hubGattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(tag, "HUB onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hubRetry = 0
                    scope.launch {
                        delay(300)  // 연결 직후 즉시 요청하면 실패하는 기기가 있음
                        if (!gatt.requestMtu(BleProtocol.DESIRED_MTU)) {
                            Log.w(tag, "HUB requestMtu=false → discoverServices 직행")
                            gatt.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(tag, "HUB GATT disconnected (status=$status)")
                    runCatching { gatt.close() }
                    hubGatt = null
                    hubReady?.complete(false)
                    hubReady = null
                    scheduleRetry(null, status)
                    updateNodeStatusList(0)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(tag, "HUB MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "HUB service discovery failed: $status")
                _connectionState.value = ConnectionState.ERROR
                hubReady?.complete(false); hubReady = null
                return
            }
            val service = gatt.getService(UUID.fromString(BleProtocol.SERVICE_UUID)) ?: run {
                Log.e(tag, "HUB service not found")
                _connectionState.value = ConnectionState.ERROR
                hubReady?.complete(false); hubReady = null
                return
            }
            val characteristic = service.getCharacteristic(
                UUID.fromString(BleProtocol.NOTIFY_CHAR_UUID)
            ) ?: run {
                Log.e(tag, "HUB notify characteristic not found")
                _connectionState.value = ConnectionState.ERROR
                hubReady?.complete(false); hubReady = null
                return
            }

            enableNotify(gatt, characteristic)

            Log.d(tag, "HUB notify enabled - 허브 연결 완료")
            _connectionState.value = ConnectionState.CONNECTED
            updateNodeStatusList(lastHubOnlineMask)
            hubReady?.complete(true); hubReady = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleHubPayload(value)

        @Deprecated("Used on Android <= 12")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleHubPayload(characteristic.value ?: return)
        }
    }

    // ────────────────────────────────────────────────────
    //  EMG GATT (좌/우 각각 동일 로직, side만 다름)
    // ────────────────────────────────────────────────────

    private fun makeEmgCallback(side: EmgSide) = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val link = emgLinks.getValue(side)
            Log.d(tag, "[${side.deviceName}] onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    link.retry = 0
                    scope.launch {
                        delay(300)
                        if (!gatt.requestMtu(EmgBleProtocol.DESIRED_MTU)) {
                            Log.w(tag, "[${side.deviceName}] requestMtu=false → discoverServices 직행")
                            gatt.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(tag, "[${side.deviceName}] GATT disconnected (status=$status)")
                    runCatching { gatt.close() }
                    link.gatt = null
                    link.rx = null
                    link.latest = null
                    link.rxBuffer.setLength(0)
                    link.ready?.complete(false)
                    link.ready = null
                    scheduleRetry(side, status)
                    updateNodeStatusList(lastHubOnlineMask)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(tag, "[${side.deviceName}] MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val link = emgLinks.getValue(side)
            fun fail(msg: String) {
                Log.e(tag, "[${side.deviceName}] $msg")
                setEmgState(side, ConnectionState.ERROR)
                link.ready?.complete(false); link.ready = null
            }

            if (status != BluetoothGatt.GATT_SUCCESS) return fail("service discovery failed: $status")

            val service = gatt.getService(UUID.fromString(EmgBleProtocol.SERVICE_UUID))
                ?: return fail("NUS service not found")
            val txChar = service.getCharacteristic(UUID.fromString(EmgBleProtocol.TX_CHAR_UUID))
                ?: return fail("TX characteristic not found")

            enableNotify(gatt, txChar)
            link.rx = service.getCharacteristic(UUID.fromString(EmgBleProtocol.RX_CHAR_UUID))

            Log.d(tag, "[${side.deviceName}] notify enabled - ${side.label} 연결 완료")

            // 연결 우선순위 HIGH 요청: 좌·우 EMG 동시 연결 시 대역폭 경합으로
            // 오른쪽 medianFreq가 33.2Hz 등 특정 값에 갇히는 문제 완화 목적.
            val prioritySet = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            Log.d(tag, "[${side.deviceName}] requestConnectionPriority(HIGH) = $prioritySet")

            setEmgState(side, ConnectionState.CONNECTED)
            updateNodeStatusList(lastHubOnlineMask)
            link.ready?.complete(true); link.ready = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handleEmgPayload(side, gatt.device?.address.orEmpty(), value)

        @Deprecated("Used on Android <= 12")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleEmgPayload(side, gatt.device?.address.orEmpty(), characteristic.value ?: return)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(BleProtocol.CCCD_UUID)) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    // ────────────────────────────────────────────────────
    //  데이터 처리
    // ────────────────────────────────────────────────────

    /** 허브 46-byte 바이너리 → TelemetryFrame (좌/우 EMG 머지) */
    private fun handleHubPayload(bytes: ByteArray) {
        val summary = BleProtocol.parseSummaryPacket(bytes) ?: run {
            Log.w(tag, "Bad packet size: ${bytes.size}")
            return
        }
        lastHubOnlineMask = summary.nodeOnlineMask

        // 패킷 손실이 있어도 프레임은 그대로 emit (테스트 단계라 손실이 많아서 화면이 멈춤)
        // 손실 배너는 emitWarningEvents에서 계속 띄워줌
        val tOffset = if (sessionStartMs > 0) {
            System.currentTimeMillis() - sessionStartMs
        } else {
            summary.hubTimeMs
        }

        updateNodeStatusList(summary.nodeOnlineMask)

        // 직접 연결된 좌/우 EMG 값 각각 추출
        val left = emgLinks.getValue(EmgSide.LEFT_THIGH).latest
        val right = emgLinks.getValue(EmgSide.RIGHT_THIGH).latest
        val rmsList = listOfNotNull(left?.rms, right?.rms)
        val mdfList = listOfNotNull(left?.medianFreq, right?.medianFreq)
        val mnfList = listOfNotNull(left?.meanFreq, right?.meanFreq)

        // 평균값 (기존 호환용, 하나의 근피로/근활성도로 표시할 때 사용)
        val emgRms = if (rmsList.isEmpty()) summary.emgRms else rmsList.average().toFloat()
        val emgMdf = if (mdfList.isEmpty()) summary.emgMedianFreqHz else mdfList.average().toFloat()
        val emgMnf = if (mnfList.isEmpty()) summary.emgMedianFreqHz else mnfList.average().toFloat()

        val frame = TelemetryFrame(
            tOffsetMs = tOffset,
            pta = mapOf(
                JointLocation.LEFT_ANKLE  to summary.leftAnklePeakG,
                JointLocation.RIGHT_ANKLE to summary.rightAnklePeakG,
                JointLocation.LEFT_KNEE   to summary.leftKneePeakG,
                JointLocation.RIGHT_KNEE  to summary.rightKneePeakG
            ),
            loadingRate = JointLocation.entries.associateWith { 0f },
            shockAttenuationLeft  = (1f / (summary.leftTransferRatio + 1f)).coerceIn(0f, 1f),
            shockAttenuationRight = (1f / (summary.rightTransferRatio + 1f)).coerceIn(0f, 1f),
            medianFreqHz  = emgMdf,
            meanFreqHz    = emgMnf,
            rms           = emgRms,
            // 좌/우 각각의 값 (null이면 해당 모듈 미연결)
            rmsLeft            = left?.rms,
            rmsRight           = right?.rms,
            medianFreqHzLeft   = left?.medianFreq,
            medianFreqHzRight  = right?.medianFreq,
            signalQuality = 1f,
            cadenceSpm    = 0,
            trunkPitchDeg = summary.trunkPitchDeg,
            trunkRollDeg  = summary.trunkRollDeg
        )
        _liveTelemetry.tryEmit(frame)

        val newFlags = summary.warningFlags and lastWarningFlags.inv()
        if (newFlags != 0) emitWarningEvents(newFlags, tOffset)
        lastWarningFlags = summary.warningFlags
    }

    /**
     * EMG NUS 텍스트 처리.
     * notification 하나에 여러 줄이 붙거나 한 줄이 잘려서 올 수 있으므로
     * side별 버퍼에 모아 '\n' 단위로 끊어서 파싱한다.
     */
    private fun handleEmgPayload(side: EmgSide, address: String, bytes: ByteArray) {
        val link = emgLinks.getValue(side)
        val buf = link.rxBuffer
        synchronized(buf) {
            buf.append(String(bytes, Charsets.UTF_8))
            var idx = buf.indexOf("\n")
            while (idx >= 0) {
                val line = buf.substring(0, idx)
                buf.delete(0, idx + 1)
                processEmgLine(side, address, line)
                idx = buf.indexOf("\n")
            }
            if (buf.length > 512) buf.setLength(0)  // 쓰레기 데이터 방어
        }
    }

    private fun processEmgLine(side: EmgSide, address: String, line: String) {
        if (line.isBlank()) return
        val f = EmgBleProtocol.parseFeaturePacket(line) ?: run {
            Log.w(tag, "[${side.deviceName}] Bad EMG packet: $line")
            return
        }
        emgLinks.getValue(side).latest = f

        val sample = EmgFeatureSample(
            deviceName     = side.deviceName,
            address        = address,
            side           = side,
            phase          = _phase.value,
            sequence       = f.sequence,
            timeMs         = f.timeMs,
            receivedAtMs   = System.currentTimeMillis(),
            rms            = f.rms,
            mav            = f.mav,
            waveformLength = f.waveformLength,
            meanFreq       = f.meanFreq,
            medianFreq     = f.medianFreq
        )
        _emgSamples.tryEmit(sample)
        csvLogger.append(sample)

        // 근전도 측정 화면용 스트림
        _emgFeatures.tryEmit(
            com.runtrack.domain.analysis.SidedEmgFeature(
                side = side,
                feature = com.runtrack.domain.analysis.FatigueAnalyzer.WindowFeature(
                    timeMs = System.currentTimeMillis(),
                    mdfHz = f.medianFreq.toDouble(),
                    rms = f.rms.toDouble()
                )
            )
        )
    }

    /** 허브 노드(발목/무릎/허리) + BLE EMG 2대를 합쳐 노드 목록 생성 */
    private fun updateNodeStatusList(onlineMask: Int) {
        val hubNodes = HUB_NODE_LABELS.map { (nodeId, label) ->
            NodeStatus(
                nodeId = "HUB-NODE-$nodeId",
                label = label,
                batteryPercent = 100,
                isOnline = HubNodeId.isOnline(onlineMask, nodeId)
            )
        }
        val emgNodes = EmgSide.entries.map { side ->
            NodeStatus(
                nodeId = "EMG-${side.storageKey}",
                label = "${side.label} (근전도)",
                batteryPercent = 100,
                isOnline = _emgConnectionStates.value[side] == ConnectionState.CONNECTED
            )
        }
        _nodeStatus.value = hubNodes + emgNodes
    }

    private fun setEmgState(side: EmgSide, state: ConnectionState) {
        _emgConnectionStates.update { it + (side to state) }
        val all = _emgConnectionStates.value.values
        _emgConnectionState.value = when {
            all.all { it == ConnectionState.CONNECTED }          -> ConnectionState.CONNECTED
            all.any { it == ConnectionState.CONNECTING ||
                    it == ConnectionState.SCANNING }           -> ConnectionState.CONNECTING
            all.any { it == ConnectionState.CONNECTED }          -> ConnectionState.CONNECTED  // 부분 연결
            all.any { it == ConnectionState.ERROR }              -> ConnectionState.ERROR
            else                                                 -> ConnectionState.DISCONNECTED
        }
        updateNodeStatusList(lastHubOnlineMask)
    }

    private fun emitWarningEvents(flags: Int, tOffset: Long) {
        // 노드 오프라인 알림
        if (flags and HubWarningFlags.NODE_OFFLINE != 0) {
            _formBreakEvents.tryEmit(
                FormBreakEvent(
                    tOffsetMs = tOffset,
                    type = FormBreakType.CADENCE_IRREGULAR,
                    severity = EventSeverity.MEDIUM,
                    affectedJoint = null,
                    description = "센서 노드 일부가 오프라인",
                    durationMs = 2000L
                )
            )
        }
        // 패킷 손실 알림 (팀 시뮬레이션 시 손실 상태를 직관적으로 파악하기 위해 표시)
        // 데이터 값은 마지막 값을 유지하되, 손실 발생 자체는 사용자에게 알림
        if (flags and HubWarningFlags.PACKET_LOSS != 0) {
            _formBreakEvents.tryEmit(
                FormBreakEvent(
                    tOffsetMs = tOffset,
                    type = FormBreakType.CADENCE_IRREGULAR,
                    severity = EventSeverity.LOW,
                    affectedJoint = null,
                    description = "데이터 패킷 손실 발생 (마지막 값 유지 중)",
                    durationMs = 2000L
                )
            )
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 20_000L      // 3대를 찾아야 하므로 여유
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val MAX_RETRY = 3
        const val RETRY_BASE_MS = 800L

        /** 허브가 보고하는 노드. EMG(id 6)는 BLE 직결로 대체하므로 제외 */
        val HUB_NODE_LABELS = listOf(
            HubNodeId.LEFT_ANKLE  to "왼쪽 발목",
            HubNodeId.RIGHT_ANKLE to "오른쪽 발목",
            HubNodeId.LEFT_KNEE   to "왼쪽 무릎",
            HubNodeId.RIGHT_KNEE  to "오른쪽 무릎",
            HubNodeId.WAIST       to "허리"
        )
    }
}
