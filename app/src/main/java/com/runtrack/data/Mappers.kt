package com.runtrack.data

import com.runtrack.data.local.EmgSampleEntity
import com.runtrack.data.local.FormBreakEventEntity
import com.runtrack.data.local.ImpactSampleEntity
import com.runtrack.data.local.LocationSummaryEntity
import com.runtrack.data.local.RunSessionEntity
import com.runtrack.data.local.RunSummaryEntity
import com.runtrack.data.local.SessionWithSummary
import com.runtrack.domain.model.EmgSample
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.ImpactSample
import com.runtrack.domain.model.LocationSummary
import com.runtrack.domain.model.RunSession
import com.runtrack.domain.model.RunSummary
import com.runtrack.domain.model.RunWithSummary

/* ──────────────  Entity → Domain  ────────────── */

fun RunSessionEntity.toDomain() = RunSession(
    sessionId, startedAt, endedAt, durationSec,
    userWeightKgSnapshot, distanceMeters, title, note, createdAt
)

fun RunSummaryEntity.toDomain(locations: List<LocationSummaryEntity>) = RunSummary(
    cumulativeImpulseTotal = cumulativeImpulseTotal,
    asymmetryIndex = asymmetryIndex,
    mdfStartHz = mdfStartHz,
    mdfEndHz = mdfEndHz,
    fatigueIndexPct = fatigueIndexPct,
    avgRms = avgRms,
    formBreakCount = formBreakCount,
    avgCadenceSpm = avgCadenceSpm,
    locationSummaries = locations.map { it.toDomain() }
)

fun LocationSummaryEntity.toDomain() = LocationSummary(
    location, ptaAvgG, ptaMaxG, ptaStdG, avgAttenuation
)

fun ImpactSampleEntity.toDomain() = ImpactSample(
    tOffsetMs, location, ptaAvgG, ptaMaxG, loadingRateAvgGPerS, cumulativeImpulse
)

fun EmgSampleEntity.toDomain() = EmgSample(
    tOffsetMs, medianFreqHz, meanFreqHz, rms, signalQuality
)

fun FormBreakEventEntity.toDomain() = FormBreakEvent(
    tOffsetMs, type, severity, affectedJoint, description, durationMs, id
)

fun SessionWithSummary.toDomainOrNull(): RunWithSummary? {
    val s = summary ?: return null
    return RunWithSummary(session.toDomain(), s.toDomain(locationSummaries))
}

/* ──────────────  Domain → Entity  ────────────── */

fun RunSession.toEntity() = RunSessionEntity(
    sessionId, startedAt, endedAt, durationSec,
    userWeightKgSnapshot, distanceMeters, title, note, createdAt
)

fun RunSummary.toEntity(sessionId: Long) = RunSummaryEntity(
    sessionId = sessionId,
    cumulativeImpulseTotal = cumulativeImpulseTotal,
    asymmetryIndex = asymmetryIndex,
    mdfStartHz = mdfStartHz,
    mdfEndHz = mdfEndHz,
    fatigueIndexPct = fatigueIndexPct,
    avgRms = avgRms,
    formBreakCount = formBreakCount,
    avgCadenceSpm = avgCadenceSpm
)

fun LocationSummary.toEntity(sessionId: Long) = LocationSummaryEntity(
    sessionId = sessionId,
    location = location,
    ptaAvgG = ptaAvgG,
    ptaMaxG = ptaMaxG,
    ptaStdG = ptaStdG,
    avgAttenuation = avgAttenuation
)

fun ImpactSample.toEntity(sessionId: Long) = ImpactSampleEntity(
    sessionId = sessionId,
    tOffsetMs = tOffsetMs,
    location = location,
    ptaAvgG = ptaAvgG,
    ptaMaxG = ptaMaxG,
    loadingRateAvgGPerS = loadingRateAvgGPerS,
    cumulativeImpulse = cumulativeImpulse
)

fun EmgSample.toEntity(sessionId: Long) = EmgSampleEntity(
    sessionId = sessionId,
    tOffsetMs = tOffsetMs,
    medianFreqHz = medianFreqHz,
    meanFreqHz = meanFreqHz,
    rms = rms,
    signalQuality = signalQuality
)

fun FormBreakEvent.toEntity(sessionId: Long) = FormBreakEventEntity(
    sessionId = sessionId,
    tOffsetMs = tOffsetMs,
    type = type,
    severity = severity,
    affectedJoint = affectedJoint,
    description = description,
    durationMs = durationMs
)
