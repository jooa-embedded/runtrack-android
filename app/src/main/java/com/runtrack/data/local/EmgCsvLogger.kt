package com.runtrack.data.local

import android.content.Context
import android.util.Log
import com.runtrack.domain.model.EmgFeatureSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EMG 샘플을 CSV로 적재하는 로거.
 *
 * - 두 모듈(왼쪽/오른쪽)의 샘플이 하나의 파일에 device_name / side 컬럼으로 구분되어 섞여 들어간다.
 * - BLE 콜백 스레드를 막지 않도록 Channel에 넣고 IO 스레드에서 순차 기록한다.
 * - 저장 위치: /Android/data/<패키지>/files/emg/emg_<sessionId>.csv
 *   (앱 전용 외부 저장소라 권한 불필요, USB로 꺼내 보기 쉬움)
 */
@Singleton
class EmgCsvLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "EmgCsvLogger"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<EmgFeatureSample>(Channel.UNLIMITED)

    @Volatile
    private var writer: BufferedWriter? = null

    /** 현재 기록 중인 파일. 기록 중이 아니면 null. */
    @Volatile
    var currentFile: File? = null
        private set

    private var writeCount = 0

    init {
        scope.launch {
            for (sample in queue) {
                val w = writer ?: continue          // 기록 중이 아니면 버림
                try {
                    w.write(sample.toCsvRow())
                    w.newLine()
                    if (++writeCount % 20 == 0) w.flush()
                } catch (e: Exception) {
                    Log.e(tag, "CSV write failed", e)
                }
            }
        }
    }

    /** 기록 시작. sessionId를 안 주면 타임스탬프로 자동 생성. */
    @Synchronized
    fun start(sessionId: String? = null): File? {
        stop()
        return try {
            val id = sessionId ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "emg")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "emg_$id.csv")
            val w = BufferedWriter(FileWriter(file, true))
            if (file.length() == 0L) {
                w.write(EmgFeatureSample.CSV_HEADER)
                w.newLine()
                w.flush()
            }
            writer = w
            currentFile = file
            writeCount = 0
            Log.d(tag, "CSV logging started: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(tag, "CSV start failed", e)
            writer = null
            currentFile = null
            null
        }
    }

    /** BLE 콜백에서 호출. 논블로킹. */
    fun append(sample: EmgFeatureSample) {
        if (writer == null) return
        queue.trySend(sample)
    }

    @Synchronized
    fun stop() {
        val w = writer ?: return
        writer = null
        scope.launch {
            try {
                w.flush()
                w.close()
                Log.d(tag, "CSV logging stopped: ${currentFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "CSV close failed", e)
            }
        }
    }
}
