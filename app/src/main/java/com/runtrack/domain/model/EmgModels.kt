package com.runtrack.domain.model

import java.util.Locale

/**
 * 근전도(EMG) 모듈 좌/우 구분.
 *
 * 두 보드는 Service / TX / RX UUID가 완전히 동일하고,
 * BLE Advertising Name으로만 구분한다.
 *
 *   MyoWare-EMG    → 오른쪽 허벅지 (right_thigh)
 *   MyoWare-EMG-2  → 왼쪽 허벅지  (left_thigh)
 *
 * ★ 주의 ★
 * "MyoWare-EMG-2".startsWith("MyoWare-EMG") == true 이므로
 * 이름 비교는 반드시 '완전 일치(==)'로 해야 한다. prefix 비교하면 좌우가 뒤바뀐다.
 */
enum class EmgSide(
    val deviceName: String,   // BLE Advertising Name (완전 일치로 매칭)
    val storageKey: String,   // CSV / DB 저장용 값
    val label: String         // UI 표시용
) {
    RIGHT_THIGH("MyoWare-EMG",   "right_thigh", "오른쪽 허벅지"),
    LEFT_THIGH ("MyoWare-EMG-2", "left_thigh",  "왼쪽 허벅지");

    companion object {
        /** 광고 이름으로 side 판별. 모르는 이름이면 null. */
        fun fromDeviceName(name: String): EmgSide? =
            entries.firstOrNull { it.deviceName == name }

        /** MAC 주소로 side 판별 (이름이 잘려서 안 잡힐 때 대비용 수동 매핑). */
        val addressOverride: MutableMap<String, EmgSide> = mutableMapOf()

        fun fromScanResult(name: String?, address: String?): EmgSide? {
            address?.let { addressOverride[it.uppercase()] }?.let { return it }
            return name?.let { fromDeviceName(it) }
        }
    }
}

/** 측정 구간. CSV의 phase 컬럼에 들어간다. */
enum class RunPhase(val key: String) {
    BEFORE_RUN("before_run"),
    DURING_RUN("during_run"),
    AFTER_RUN("after_run")
}

/**
 * EMG 모듈 한 패킷 = 저장 레코드 1행.
 *
 * CSV 스키마:
 * device_name,side,phase,sequence,time_ms,rms,mav,waveform_length,mean_freq,median_freq
 * MyoWare-EMG,right_thigh,before_run,1,1024,18.20,14.80,1320.50,58.30,45.00
 */
data class EmgFeatureSample(
    val deviceName: String,
    val address: String,          // MAC (CSV엔 안 넣지만 DB 저장/디버깅용)
    val side: EmgSide,
    val phase: RunPhase,
    val sequence: Int,
    val timeMs: Long,             // ESP32 millis() 원본 타임스탬프
    val receivedAtMs: Long,       // 폰 수신 시각 (System.currentTimeMillis())
    val rms: Float,
    val mav: Float,
    val waveformLength: Float,
    val meanFreq: Float,
    val medianFreq: Float
) {
    fun toCsvRow(): String = String.format(
        Locale.US,
        "%s,%s,%s,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f",
        deviceName, side.storageKey, phase.key, sequence, timeMs,
        rms, mav, waveformLength, meanFreq, medianFreq
    )

    companion object {
        const val CSV_HEADER =
            "device_name,side,phase,sequence,time_ms,rms,mav,waveform_length,mean_freq,median_freq"
    }
}
