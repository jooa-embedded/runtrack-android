package com.runtrack.data.repository

import com.runtrack.data.datasource.SeedDataGenerator
import com.runtrack.data.local.RunDao
import com.runtrack.data.toDomain
import com.runtrack.data.toDomainOrNull
import com.runtrack.data.toEntity
import com.runtrack.domain.RunRepository
import com.runtrack.domain.UserPreferencesRepository
import com.runtrack.domain.model.EmgSample
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.ImpactSample
import com.runtrack.domain.model.RunDetail
import com.runtrack.domain.model.RunSession
import com.runtrack.domain.model.RunSummary
import com.runtrack.domain.model.RunWithSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.LocationSummary
import kotlin.math.abs

@Singleton
class RunRepositoryImpl @Inject constructor(
    private val dao: RunDao,
    private val seedGenerator: SeedDataGenerator,
    private val userPreferences: UserPreferencesRepository
) : RunRepository {

    override fun observeRuns(): Flow<List<RunWithSummary>> =
        dao.observeSessionsWithSummary().map { list ->
            list.mapNotNull { it.toDomainOrNull() }
        }

    override suspend fun getRunDetail(sessionId: Long): RunDetail? =
        withContext(Dispatchers.IO) {
            val session = dao.getSession(sessionId)?.toDomain() ?: return@withContext null
            val summaryEntity = dao.getSummary(sessionId) ?: return@withContext null

            // visible 데이터
            val visibleLocEntities = dao.getVisibleLocationSummaries(sessionId)
            val visibleImpactEntities = dao.getVisibleImpactSamples(sessionId)
            val visibleEventEntities = dao.getVisibleEvents(sessionId)

            val visibleLocationSummaries = visibleLocEntities.map { it.toDomain() }
            val visibleImpactSamples = visibleImpactEntities.map { it.toDomain() }
            val visibleEvents = visibleEventEntities.map { it.toDomain() }

            // hidden 데이터 (상세 화면 배너에서 사용)
            val hiddenLocs = dao.getHiddenLocationSummaries(sessionId).map { it.location }
            val hiddenEvts = dao.getHiddenEvents(sessionId).map { it.toDomain() }

            // 원본 요약을 baseline으로, visible 기준 재계산
            val storedSummary = summaryEntity.toDomain(visibleLocEntities)
            val recomputed = recomputeSummary(
                original = storedSummary,
                visibleLocationSummaries = visibleLocationSummaries,
                visibleImpactSamples = visibleImpactSamples,
                visibleEventsCount = visibleEvents.size
            )

            RunDetail(
                session = session,
                summary = recomputed,
                impactSamples = visibleImpactSamples,
                emgSamples = dao.getEmgSamples(sessionId).map { it.toDomain() },
                events = visibleEvents,
                hiddenLocations = hiddenLocs,
                hiddenEvents = hiddenEvts
            )
        }

    override suspend fun getRecentRuns(limit: Int): List<RunWithSummary> =
        withContext(Dispatchers.IO) {
            dao.getRecentSessionsWithSummary(limit).mapNotNull { it.toDomainOrNull() }
        }

    override suspend fun saveCompletedRun(
        session: RunSession,
        impactSamples: List<ImpactSample>,
        emgSamples: List<EmgSample>,
        events: List<FormBreakEvent>,
        summary: RunSummary
    ): Long = withContext(Dispatchers.IO) {
        dao.saveRun(
            session = session.toEntity(),
            summary = summary.toEntity(0),
            locationSummaries = summary.locationSummaries.map { it.toEntity(0) },
            impactSamples = impactSamples.map { it.toEntity(0) },
            emgSamples = emgSamples.map { it.toEntity(0) },
            events = events.map { it.toEntity(0) }
        )
    }

    override suspend fun deleteRun(sessionId: Long) =
        withContext(Dispatchers.IO) { dao.deleteSession(sessionId) }

    override suspend fun updateNote(sessionId: Long, note: String) =
        withContext(Dispatchers.IO) { dao.updateNote(sessionId, note) }

    override suspend fun setJointHidden(
        sessionId: Long, location: JointLocation, hidden: Boolean
    ) = withContext(Dispatchers.IO) { dao.setJointHidden(sessionId, location, hidden) }

    override suspend fun deleteJoint(sessionId: Long, location: JointLocation) =
        withContext(Dispatchers.IO) { dao.deleteJoint(sessionId, location) }

    override suspend fun setEventHidden(eventId: Long, hidden: Boolean) =
        withContext(Dispatchers.IO) { dao.setEventHidden(eventId, hidden) }

    override suspend fun deleteEvent(eventId: Long) =
        withContext(Dispatchers.IO) { dao.deleteEvent(eventId) }

    override suspend fun updateTitle(sessionId: Long, title: String) =
        withContext(Dispatchers.IO) { dao.updateTitle(sessionId, title) }

    override suspend fun runCount(): Int =
        withContext(Dispatchers.IO) { dao.count() }

    override suspend fun seedSampleDataIfEmpty() = withContext(Dispatchers.IO) {
        if (dao.count() > 0) return@withContext
        val weight = userPreferences.weightKg.first().takeIf { it > 0f } ?: 65f
        seedGenerator.generate(count = 3, userWeightKg = weight).forEach { run ->
            dao.saveRun(
                session = run.session.toEntity(),
                summary = run.summary.toEntity(0),
                locationSummaries = run.summary.locationSummaries.map { it.toEntity(0) },
                impactSamples = run.impactSamples.map { it.toEntity(0) },
                emgSamples = run.emgSamples.map { it.toEntity(0) },
                events = run.events.map { it.toEntity(0) }
            )
        }
    }

    override suspend fun clearAllRuns() =
        withContext(Dispatchers.IO) { dao.deleteAll() }
}

/**
 * 숨김 제외 데이터로 RunSummary를 다시 계산한다.
 * - locationSummaries, cumulativeImpulseTotal, asymmetryIndex, formBreakCount만 새로 계산.
 * - avgRms, mdfStartHz, mdfEndHz, fatigueIndexPct, avgCadenceSpm은 IMU 부위 숨김과 무관하므로
 *   원본 값 유지.
 */
private fun recomputeSummary(
    original: RunSummary,
    visibleLocationSummaries: List<LocationSummary>,
    visibleImpactSamples: List<ImpactSample>,
    visibleEventsCount: Int
): RunSummary {
    val cumTotal = visibleImpactSamples
        .groupBy { it.location }
        .map { (_, samples) -> samples.maxByOrNull { it.tOffsetMs }?.cumulativeImpulse ?: 0f }
        .sum()

    val leftAnkle = visibleLocationSummaries
        .firstOrNull { it.location == JointLocation.LEFT_ANKLE }?.ptaAvgG ?: 0f
    val rightAnkle = visibleLocationSummaries
        .firstOrNull { it.location == JointLocation.RIGHT_ANKLE }?.ptaAvgG ?: 0f

    val bothPresent = leftAnkle > 0f && rightAnkle > 0f
    val asym = if (bothPresent)
        abs(leftAnkle - rightAnkle) / ((leftAnkle + rightAnkle) / 2f) * 100f
    else 0f
    val leftHeavier = if (bothPresent) leftAnkle > rightAnkle else null    // ← 추가

    return original.copy(
        cumulativeImpulseTotal = cumTotal,
        asymmetryIndex = asym,
        formBreakCount = visibleEventsCount,
        locationSummaries = visibleLocationSummaries,
        asymmetryLeftHeavier = leftHeavier                                 // ← 추가
    )
}
