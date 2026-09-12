package com.runtrack.data.datasource

import android.Manifest
import android.os.Build

/**
 * BLE 사용에 필요한 권한을 OS 버전별로 반환.
 * Android 12+ (API 31+): BLUETOOTH_SCAN, BLUETOOTH_CONNECT
 * Android 11 이하       : 위치 권한 필수 (스캔에 사용됨)
 */
object BlePermissions {

    val required: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}