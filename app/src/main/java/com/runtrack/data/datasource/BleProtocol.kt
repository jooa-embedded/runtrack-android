package com.runtrack.data.datasource

import com.runtrack.domain.model.EmgSide
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 허브(JOLNON_RUN_HUB) BLE 프로토콜 정의.
 *
 * 참고 파일: ESPNowHubReceiver.ino, HubPacketTypes.h, HubState.h
 * 프로토콜 버전: HUB_PROTOCOL_VERSION = 1
 *
 * BLE Notify로 받는 데이터는 46-byte binary struct (HubSummaryPacket).
 * 텍스트가 아니라 바이트 단위로 little-endian 정수/float을 읽어야 함.
 */
object BleProtocol {
    const val HUB_DEVICE_NAME = "RunHub-ESP32S3"
    const val SERVICE_UUID = "7d8f0001-7a5b-4a9d-bc3e-010203040506"
    const val NOTIFY_CHAR_UUID = "7d8f0002-7a5b-4a9d-bc3e-010203040506"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val EXPECTED_PACKET_SIZE = 46  // HubSummaryPacket 고정 크기
    const val DESIRED_MTU = 64

    /**
     * HubSummaryPacket 디코딩 결과.
     * C struct (HubPacketTypes.h) 의 HubSummaryPacket과 1:1 매핑.
     *
     * Byte layout (little-endian, packed):
     *  [0..3]   uint32 hub_time_ms
     *  [4]      uint8  node_online_mask
     *  [5]      uint8  warning_flags
     *  [6..9]   float  left_ankle_peak_g
     *  [10..13] float  right_ankle_peak_g
     *  [14..17] float  left_knee_peak_g
     *  [18..21] float  right_knee_peak_g
     *  [22..25] float  left_transfer_ratio
     *  [26..29] float  right_transfer_ratio
     *  [30..33] float  trunk_pitch_deg
     *  [34..37] float  trunk_roll_deg
     *  [38..41] float  emg_rms
     *  [42..45] float  emg_median_freq_hz
     */
    data class HubSummary(
        val hubTimeMs: Long,
        val nodeOnlineMask: Int,
        val warningFlags: Int,
        val leftAnklePeakG: Float,
        val rightAnklePeakG: Float,
        val leftKneePeakG: Float,
        val rightKneePeakG: Float,
        val leftTransferRatio: Float,
        val rightTransferRatio: Float,
        val trunkPitchDeg: Float,
        val trunkRollDeg: Float,
        val emgRms: Float,
        val emgMedianFreqHz: Float
    )

    /** 46-byte 바이너리를 HubSummary로 파싱. 크기 안 맞으면 null 반환. */
    fun parseSummaryPacket(bytes: ByteArray): HubSummary? {
        if (bytes.size != EXPECTED_PACKET_SIZE) return null
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            HubSummary(
                hubTimeMs           = buf.int.toLong() and 0xFFFFFFFFL,
                nodeOnlineMask      = buf.get().toInt() and 0xFF,
                warningFlags        = buf.get().toInt() and 0xFF,
                leftAnklePeakG      = buf.float,
                rightAnklePeakG     = buf.float,
                leftKneePeakG       = buf.float,
                rightKneePeakG      = buf.float,
                leftTransferRatio   = buf.float,
                rightTransferRatio  = buf.float,
                trunkPitchDeg       = buf.float,
                trunkRollDeg        = buf.float,
                emgRms              = buf.float,
                emgMedianFreqHz     = buf.float
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * NodeId 정의 (HubPacketTypes.h 기준).
 * node_online_mask에서 (node_id - 1) 비트가 해당 노드 상태.
 */
object HubNodeId {
    const val LEFT_ANKLE  = 1
    const val RIGHT_ANKLE = 2
    const val LEFT_KNEE   = 3
    const val RIGHT_KNEE  = 4
    const val WAIST       = 5
    const val EMG         = 6

    /** mask에서 특정 노드가 온라인인지 확인 */
    fun isOnline(mask: Int, nodeId: Int): Boolean =
        (mask and (1 shl (nodeId - 1))) != 0
}

/**
 * warning_flags 비트 정의 (HubPacketTypes.h의 WarningFlags).
 * 이건 시스템 경고용이라 사용자 자세/충격 이벤트와는 의미가 다름.
 */
object HubWarningFlags {
    const val NONE          = 0
    const val PACKET_LOSS   = 1 shl 0   // 1 - 패킷 손실 발생
    const val NODE_OFFLINE  = 1 shl 1   // 2 - 노드 오프라인
    const val BAD_PACKET    = 1 shl 2   // 4 - 잘못된 패킷 수신

    fun decode(flags: Int): List<String> = buildList {
        if (flags and PACKET_LOSS   != 0) add("패킷 손실")
        if (flags and NODE_OFFLINE  != 0) add("노드 오프라인")
        if (flags and BAD_PACKET    != 0) add("잘못된 패킷")
    }
}

/**
 * 근전도 모듈(MyoWare-EMG) BLE 프로토콜 정의.
 *
 * Nordic UART Service(NUS) 기반 텍스트 CSV 전송.
 * ESP32 펌웨어의 sendFeatureBle() 함수가 보내는 포맷:
 *   "F,{seq},{timeMs},{rms},{mav},{waveformLength},{meanFreq},{medianFreq},{windowMs}\n"
 *
 * MTU 128 설정이므로 ~80자 메시지는 한 notification에 수신됨.
 *
 * ★ 두 대 운용 ★
 * 오른쪽/왼쪽 허벅지 보드가 Service/TX/RX UUID를 100% 동일하게 쓴다.
 * 구분은 오직 Advertising Name(EmgSide.deviceName)으로 한다.
 */
object EmgBleProtocol {
    /** 두 보드가 공유하는 NUS UUID들 (동일해도 무방) */
    const val SERVICE_UUID   = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val TX_CHAR_UUID   = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // Notify (ESP32→App)
    const val RX_CHAR_UUID   = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // Write  (App→ESP32)

    const val DESIRED_MTU = 128

    /** 연결 대상 기기 이름 목록 (좌/우) */
    val DEVICE_NAMES: List<String> = EmgSide.entries.map { it.deviceName }

    /**
     * Feature 패킷 파싱 결과.
     * ESP32 sendFeatureBle()의 CSV 필드와 1:1 매핑.
     */
    data class EmgFeature(
        val sequence: Int,           // 패킷 일련번호
        val timeMs: Long,            // ESP32 millis() 타임스탬프
        val rms: Float,              // EMG RMS 값
        val mav: Float,              // Mean Absolute Value
        val waveformLength: Float,   // Waveform Length
        val meanFreq: Float,         // Mean Frequency (Hz)
        val medianFreq: Float,       // Median Frequency (Hz)
        val windowMs: Int            // 분석 윈도우 크기 (ms)
    )

    /**
     * CSV 텍스트 → EmgFeature 파싱.
     * 포맷: "F,seq,timeMs,rms,mav,wl,meanFreq,medianFreq,windowMs\n"
     * 파싱 실패 시 null 반환.
     */
    fun parseFeaturePacket(text: String): EmgFeature? {
        val parts = text.trim().split(',')
        if (parts.size != 9 || parts[0] != "F") return null
        return try {
            EmgFeature(
                sequence       = parts[1].toInt(),
                timeMs         = parts[2].toLong(),
                rms            = parts[3].toFloat(),
                mav            = parts[4].toFloat(),
                waveformLength = parts[5].toFloat(),
                meanFreq       = parts[6].toFloat(),
                medianFreq     = parts[7].toFloat(),
                windowMs       = parts[8].toInt()
            )
        } catch (e: Exception) {
            null
        }
    }
}
