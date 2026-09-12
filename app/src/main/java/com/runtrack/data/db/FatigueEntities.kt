package com.runtrack.data.db //이따가 할 일 : 근전도+허리도 가상신호 나오게 만들기, 프로필별로 각각 데이터 저장되는건지? 이전기록들 싹 다. 프로필 재설정하면 그에 맞는 러닝기록이 나와야함.

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 근전도 측정 세션 1회 = 운동 전 + 운동 후 + FI. post*가 null이면 진행 중. */
@Entity(tableName = "fatigue_session")
data class FatigueSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,

    val preMdfLeftHz: Double,
    val preMdfRightHz: Double,
    val preRmsLeft: Double,
    val preRmsRight: Double,
    val preWindowCount: Int,

    val postMdfLeftHz: Double? = null,
    val postMdfRightHz: Double? = null,
    val postRmsLeft: Double? = null,
    val postRmsRight: Double? = null,
    val postWindowCount: Int? = null,
    val postMeasuredAtMs: Long? = null,

    val fiLeftPercent: Double? = null,
    val fiRightPercent: Double? = null,
)

/** 그래프용 윈도우별 MDF 시계열 */
@Entity(tableName = "mdf_point", indices = [Index("sessionId")])
data class MdfPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val phase: String,          // "PRE" | "POST"
    val timeMs: Long,
    val mdfLeftHz: Double,
    val mdfRightHz: Double,
)

@Dao
interface FatigueDao {
    @Insert suspend fun insertSession(s: FatigueSession): Long
    @Update suspend fun updateSession(s: FatigueSession)
    @Query("SELECT * FROM fatigue_session ORDER BY startedAtMs DESC")
    fun sessions(): Flow<List<FatigueSession>>
    @Query("SELECT * FROM fatigue_session WHERE id = :id")
    suspend fun session(id: Long): FatigueSession?

    @Insert suspend fun insertPoints(points: List<MdfPoint>)
    @Query("SELECT * FROM mdf_point WHERE sessionId = :sessionId ORDER BY timeMs")
    suspend fun pointsOf(sessionId: Long): List<MdfPoint>
}