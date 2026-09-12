package com.runtrack.domain.analysis

import com.runtrack.domain.model.EmgSide
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/** 한쪽 채널의 특징값 1건 (좌/우 구분 포함) */
data class SidedEmgFeature(
    val side: EmgSide,
    val feature: FatigueAnalyzer.WindowFeature,
)

object FatigueAnalyzer {

    const val EMG_BAND_LOW_HZ = 20.0
    const val EMG_BAND_HIGH_HZ = 450.0

    data class WindowFeature(
        val timeMs: Long,
        val mdfHz: Double,
        val rms: Double,
    )

    data class MeasurementSummary(
        val mdfMeanHz: Double,
        val mdfStdHz: Double,
        val rmsMean: Double,
        val windowCount: Int,
    )

    data class FatigueResult(
        val fatigueIndexPercent: Double,  // (pre-post)/pre*100, 클수록 피로 큼
        val mdfPreHz: Double,
        val mdfPostHz: Double,
        val rmsChangePercent: Double,
    )

    fun summarize(features: List<WindowFeature>): MeasurementSummary {
        require(features.isNotEmpty())
        val mdfs = features.map { it.mdfHz }
        val mean = mdfs.average()
        val std = sqrt(mdfs.sumOf { (it - mean) * (it - mean) } / mdfs.size)
        return MeasurementSummary(
            mdfMeanHz = mean,
            mdfStdHz = std,
            rmsMean = features.map { it.rms }.average(),
            windowCount = features.size,
        )
    }

    /** FI(%) = (MDF_pre − MDF_post) / MDF_pre × 100 */
    fun compare(pre: MeasurementSummary, post: MeasurementSummary): FatigueResult =
        FatigueResult(
            fatigueIndexPercent = (pre.mdfMeanHz - post.mdfMeanHz) / pre.mdfMeanHz * 100.0,
            mdfPreHz = pre.mdfMeanHz,
            mdfPostHz = post.mdfMeanHz,
            rmsChangePercent = (post.rmsMean - pre.rmsMean) / pre.rmsMean * 100.0,
        )
}