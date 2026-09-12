package com.runtrack.domain

import com.runtrack.domain.model.EmgSample
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.ImpactSample
import com.runtrack.domain.model.RunDetail
import com.runtrack.domain.model.RunSession
import com.runtrack.domain.model.RunSummary
import com.runtrack.domain.model.RunWithSummary
import kotlinx.coroutines.flow.Flow
import com.runtrack.domain.model.JointLocation

/** Persistence + aggregation of completed runs. */
interface RunRepository {

    fun observeRuns(): Flow<List<RunWithSummary>>

    suspend fun getRunDetail(sessionId: Long): RunDetail?

    suspend fun getRecentRuns(limit: Int): List<RunWithSummary>

    /** Persists a finished run (session + samples + events) and computes its summary. */
    suspend fun saveCompletedRun(
        session: RunSession,
        impactSamples: List<ImpactSample>,
        emgSamples: List<EmgSample>,
        events: List<FormBreakEvent>,
        summary: RunSummary
    ): Long

    suspend fun deleteRun(sessionId: Long)

    suspend fun updateNote(sessionId: Long, note: String)

    /** 부위(발목/무릎) 하나를 결과에서 숨기거나 다시 되살린다. 원본 데이터는 유지. */
    suspend fun setJointHidden(sessionId: Long, location: JointLocation, hidden: Boolean)

    /** 부위 데이터를 DB에서 완전 삭제. 되돌릴 수 없음. */
    suspend fun deleteJoint(sessionId: Long, location: JointLocation)

    /** 자세 무너짐 이벤트 하나를 숨기거나 되살린다. */
    suspend fun setEventHidden(eventId: Long, hidden: Boolean)

    /** 자세 무너짐 이벤트를 DB에서 완전 삭제. */
    suspend fun deleteEvent(eventId: Long)

    suspend fun updateTitle(sessionId: Long, title: String)

    suspend fun runCount(): Int

    /** Seeds demo history on first launch (or on demand). */
    suspend fun seedSampleDataIfEmpty()

    suspend fun clearAllRuns()
}

/** User profile + app settings, backed by DataStore. */
interface UserPreferencesRepository {

    val onboardingComplete: Flow<Boolean>
    val userName: Flow<String>
    val weightKg: Flow<Float>
    val heightCm: Flow<Float>
    val telemetryHz: Flow<Int>
    val alertSoundEnabled: Flow<Boolean>
    val alertHapticEnabled: Flow<Boolean>

    suspend fun completeOnboarding(name: String, weightKg: Float, heightCm: Float)
    suspend fun updateProfile(name: String, weightKg: Float, heightCm: Float)
    suspend fun setTelemetryHz(hz: Int)
    suspend fun setAlertSound(enabled: Boolean)
    suspend fun setAlertHaptic(enabled: Boolean)
}
