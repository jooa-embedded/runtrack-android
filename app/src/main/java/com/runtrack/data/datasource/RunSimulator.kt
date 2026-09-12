package com.runtrack.data.datasource

import com.runtrack.domain.model.EventSeverity
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.FormBreakType
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.TelemetryFrame
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic, physiologically-plausible run generator.
 *
 * Shared by [MockRunDataSource] (live, real-time emission) and
 * [SeedDataGenerator] (offline, builds historical sessions) so both paths
 * produce data of identical shape and realism.
 *
 * @param fitness 0f (poor form) .. 1f (great form). Higher fitness = lower
 *        impact, better shock absorption, slower fatigue, fewer form breaks.
 */
class RunSimulator(
    private val seed: Long,
    private val fitness: Float,
    private val runDurationSec: Int
) {
    private val rng = Random(seed)

    private data class BreakWindow(
        val startMs: Long,
        val endMs: Long,
        val type: FormBreakType,
        val severity: EventSeverity,
        val joint: JointLocation?
    )

    /** A small left/right load imbalance, fixed for the whole run. */
    private val asymmetryBias: Float =
        (0.015f + rng.nextFloat() * 0.075f) * (if (rng.nextBoolean()) 1f else -1f)

    /** 좌/우 EMG 비대칭 (러닝마다 다름) */
    private val emgAsymmetry: Float = 0.94f + rng.nextFloat() * 0.12f

    private val breakWindows: List<BreakWindow> = buildBreakWindows()

    private fun buildBreakWindows(): List<BreakWindow> {
        val count = (2 + (1f - fitness) * 4f).roundToInt().coerceIn(1, 6)
        val totalMs = runDurationSec * 1000L
        return (0 until count).map {
            // bias events toward the later 65 % of the run
            val start = (totalMs * (0.35 + rng.nextDouble() * 0.6)).toLong()
            val duration = (6000 + rng.nextInt(9000)).toLong()
            val severityRoll = rng.nextFloat() + (1f - fitness) * 0.3f
            val severity = when {
                severityRoll > 1.05f -> EventSeverity.HIGH
                severityRoll > 0.6f -> EventSeverity.MEDIUM
                else -> EventSeverity.LOW
            }
            val type = FormBreakType.entries[rng.nextInt(FormBreakType.entries.size)]
            val joint = when (type) {
                FormBreakType.ASYMMETRY, FormBreakType.CADENCE_IRREGULAR -> null
                else -> if (rng.nextBoolean()) JointLocation.knees.random(kotlin.random.Random(seed + it))
                else JointLocation.entries[rng.nextInt(4)]
            }
            BreakWindow(start, start + duration, type, severity, joint)
        }.sortedBy { it.startMs }
    }

    /** All form-break events for the whole run (used by seed generation). */
    fun allEvents(): List<FormBreakEvent> = breakWindows.map { it.toEvent() }

    /** Event that *starts* within [fromMs, toMs), if any (used by live emission). */
    fun eventStartingIn(fromMs: Long, toMs: Long): FormBreakEvent? =
        breakWindows.firstOrNull { it.startMs in fromMs until toMs }?.toEvent()

    private fun BreakWindow.toEvent() = FormBreakEvent(
        tOffsetMs = startMs,
        type = type,
        severity = severity,
        affectedJoint = joint,
        description = buildString {
            append(type.displayName)
            joint?.let { append(" · ${it.displayName}") }
            append(" 감지 (${severity.displayName})")
        },
        durationMs = endMs - startMs
    )

    /** A single telemetry frame at [elapsedMs] into the run. */
    fun frameAt(elapsedMs: Long): TelemetryFrame {
        val progress = (elapsedMs / 1000f / runDurationSec).coerceIn(0f, 1f)
        val fatigue = progress * (0.85f - 0.40f * fitness)        // 0 .. ~0.6
        val active = breakWindows.firstOrNull { elapsedMs in it.startMs until it.endMs }
        val breakIntensity = when (active?.severity) {
            EventSeverity.LOW -> 0.4f
            EventSeverity.MEDIUM -> 0.7f
            EventSeverity.HIGH -> 1.0f
            null -> 0f
        }

        val ankleBase = 7.0f + (1f - fitness) * 1.6f
        val kneeBase = 5.0f + (1f - fitness) * 1.3f

        val pta = JointLocation.entries.associateWith { joint ->
            val base = if (joint.isKnee) kneeBase else ankleBase
            val sideMul = if (joint.isLeft) 1f + asymmetryBias else 1f - asymmetryBias
            val breakBoost = breakIntensity * 2.6f * (if (joint.isKnee) 1.25f else 0.8f)
            val v = (base + fatigue * 2.3f + breakBoost) * sideMul +
                    gaussian() * 0.45f
            v.coerceAtLeast(0.5f)
        }
        val loadingRate = pta.mapValues { (_, v) -> v * 24f + gaussian() * 6f }

        val attnBase = 0.72f + fitness * 0.12f
        fun attenuation() =
            (attnBase - fatigue * 0.12f - breakIntensity * 0.22f + gaussian() * 0.02f)
                .coerceIn(0.35f, 0.95f)

        val startMdf = 95f + fitness * 15f
        val mdf = (startMdf -
                progress * startMdf * (0.12f + (1f - fitness) * 0.16f) +
                gaussian() * 1.6f).coerceAtLeast(40f)

        val rms = (0.30f + progress * 0.13f + breakIntensity * 0.05f + gaussian() * 0.025f)
            .coerceIn(0.05f, 1f)
        val signalQuality = (0.93f - breakIntensity * 0.08f + gaussian() * 0.03f)
            .coerceIn(0.5f, 1f)
        val cadence = (168f + fitness * 8f - fatigue * 6f + gaussian() * 2.5f)
            .roundToInt().coerceIn(150, 195)

        // ── 허리 자세 (BNO086 시뮬레이션) ──
        // 러너가 달릴 때 몸이 자연스럽게 앞뒤·좌우로 움직이는 걸 sin파로 표현
        // 피로 누적 시 상체 각도 편차가 커지는 경향 반영
        val strideT = elapsedMs / 500f  // 발디딤 주기 (0.5초/스텝 정도)
        val trunkPitchDeg = (
                8f +                                            // 기본 앞기울기 8도 (러닝 자세)
                        fatigue * 4f +                                  // 피로로 앞으로 더 굽음
                        sin(strideT.toDouble()).toFloat() * 2.5f +      // 발디딤에 맞춰 흔들림
                        breakIntensity * 6f +                           // 자세 무너짐 시 크게 튐
                        gaussian() * 0.8f
                )
        val trunkRollDeg = (
                asymmetryBias * 15f +                           // 좌우 비대칭이 롤에 반영
                        sin((strideT * 0.5f).toDouble()).toFloat() * 3f + // 좌우 흔들림
                        breakIntensity * 4f +
                        gaussian() * 0.6f
                )

        // ── 좌/우 EMG 값 (근전도 모듈 2대 시뮬레이션) ──
        // 오른쪽은 emgAsymmetry로 살짝 다르게, 피로 누적 시 MDF 하락 / RMS 상승
        val mdfLeft = mdf
        val mdfRight = mdf * emgAsymmetry + gaussian() * 1.5f
        val rmsLeft = rms
        val rmsRight = (rms * (2f - emgAsymmetry) + gaussian() * 0.02f).coerceIn(0.05f, 1f)

        return TelemetryFrame(
            tOffsetMs = elapsedMs,
            pta = pta,
            loadingRate = loadingRate,
            shockAttenuationLeft = attenuation(),
            shockAttenuationRight = attenuation(),
            medianFreqHz = mdf,
            meanFreqHz = mdf * 1.12f,
            rms = rms,
            signalQuality = signalQuality,
            cadenceSpm = cadence,
            trunkPitchDeg = trunkPitchDeg,
            trunkRollDeg = trunkRollDeg,
            rmsLeft = rmsLeft,
            rmsRight = rmsRight,
            medianFreqHzLeft = mdfLeft,
            medianFreqHzRight = mdfRight
        )
    }

    private fun gaussian(): Float = rng.nextGaussian().toFloat()
}