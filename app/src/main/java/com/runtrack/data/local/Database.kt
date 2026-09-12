package com.runtrack.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.runtrack.domain.model.EventSeverity
import com.runtrack.domain.model.FormBreakType
import com.runtrack.domain.model.JointLocation
import kotlinx.coroutines.flow.Flow

/* ─────────────────────────  Entities  ───────────────────────── */

@Entity(tableName = "run_session")
data class RunSessionEntity(
    @PrimaryKey(autoGenerate = true) val sessionId: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Int,
    val userWeightKgSnapshot: Float,
    val distanceMeters: Float?,
    val title: String,
    val note: String,
    val createdAt: Long
)

@Entity(
    tableName = "run_summary",
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RunSummaryEntity(
    @PrimaryKey val sessionId: Long,
    val cumulativeImpulseTotal: Float,
    val asymmetryIndex: Float,
    val mdfStartHz: Float,
    val mdfEndHz: Float,
    val fatigueIndexPct: Float,
    val avgRms: Float,
    val formBreakCount: Int,
    val avgCadenceSpm: Int
)

@Entity(
    tableName = "location_summary",
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class LocationSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val location: JointLocation,
    val ptaAvgG: Float,
    val ptaMaxG: Float,
    val ptaStdG: Float,
    val avgAttenuation: Float,
    val isHidden: Boolean = false
)

@Entity(
    tableName = "impact_sample",
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class ImpactSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tOffsetMs: Long,
    val location: JointLocation,
    val ptaAvgG: Float,
    val ptaMaxG: Float,
    val loadingRateAvgGPerS: Float,
    val cumulativeImpulse: Float,
    val isHidden: Boolean = false
)

@Entity(
    tableName = "emg_sample",
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class EmgSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tOffsetMs: Long,
    val medianFreqHz: Float,
    val meanFreqHz: Float,
    val rms: Float,
    val signalQuality: Float
)

@Entity(
    tableName = "form_break_event",
    foreignKeys = [ForeignKey(
        entity = RunSessionEntity::class,
        parentColumns = ["sessionId"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class FormBreakEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tOffsetMs: Long,
    val type: FormBreakType,
    val severity: EventSeverity,
    val affectedJoint: JointLocation?,
    val description: String,
    val durationMs: Long,
    val isHidden: Boolean = false
)

/** One-shot read of a session plus its summary, for list screens. */
data class SessionWithSummary(
    @Embedded val session: RunSessionEntity,
    @Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val summary: RunSummaryEntity?,
    @Relation(parentColumn = "sessionId", entityColumn = "sessionId")
    val locationSummaries: List<LocationSummaryEntity>
)

/* ─────────────────────────  Converters  ───────────────────────── */

class RunConverters {
    @TypeConverter fun jointToString(v: JointLocation?): String? = v?.name
    @TypeConverter fun stringToJoint(v: String?): JointLocation? = v?.let { JointLocation.valueOf(it) }

    @TypeConverter fun typeToString(v: FormBreakType): String = v.name
    @TypeConverter fun stringToType(v: String): FormBreakType = FormBreakType.valueOf(v)

    @TypeConverter fun severityToString(v: EventSeverity): String = v.name
    @TypeConverter fun stringToSeverity(v: String): EventSeverity = EventSeverity.valueOf(v)
}

/* ─────────────────────────  DAO  ───────────────────────── */

@Dao
abstract class RunDao {

    @Insert
    abstract suspend fun insertSession(session: RunSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSummary(summary: RunSummaryEntity)

    @Insert
    abstract suspend fun insertLocationSummaries(items: List<LocationSummaryEntity>)

    @Insert
    abstract suspend fun insertImpactSamples(items: List<ImpactSampleEntity>)

    @Insert
    abstract suspend fun insertEmgSamples(items: List<EmgSampleEntity>)

    @Insert
    abstract suspend fun insertEvents(items: List<FormBreakEventEntity>)

    /** Atomic save of a finished run. Returns the generated sessionId. */
    @Transaction
    open suspend fun saveRun(
        session: RunSessionEntity,
        summary: RunSummaryEntity,
        locationSummaries: List<LocationSummaryEntity>,
        impactSamples: List<ImpactSampleEntity>,
        emgSamples: List<EmgSampleEntity>,
        events: List<FormBreakEventEntity>
    ): Long {
        val id = insertSession(session)
        insertSummary(summary.copy(sessionId = id))
        insertLocationSummaries(locationSummaries.map { it.copy(sessionId = id) })
        insertImpactSamples(impactSamples.map { it.copy(sessionId = id) })
        insertEmgSamples(emgSamples.map { it.copy(sessionId = id) })
        insertEvents(events.map { it.copy(sessionId = id) })
        return id
    }

    @Transaction
    @Query("SELECT * FROM run_session ORDER BY startedAt DESC")
    abstract fun observeSessionsWithSummary(): Flow<List<SessionWithSummary>>

    @Transaction
    @Query("SELECT * FROM run_session ORDER BY startedAt DESC LIMIT :limit")
    abstract suspend fun getRecentSessionsWithSummary(limit: Int): List<SessionWithSummary>

    @Query("SELECT * FROM run_session WHERE sessionId = :id")
    abstract suspend fun getSession(id: Long): RunSessionEntity?

    @Query("SELECT * FROM run_summary WHERE sessionId = :id")
    abstract suspend fun getSummary(id: Long): RunSummaryEntity?

    @Query("SELECT * FROM location_summary WHERE sessionId = :id")
    abstract suspend fun getLocationSummaries(id: Long): List<LocationSummaryEntity>

    @Query("SELECT * FROM impact_sample WHERE sessionId = :id ORDER BY tOffsetMs ASC")
    abstract suspend fun getImpactSamples(id: Long): List<ImpactSampleEntity>

    @Query("SELECT * FROM emg_sample WHERE sessionId = :id ORDER BY tOffsetMs ASC")
    abstract suspend fun getEmgSamples(id: Long): List<EmgSampleEntity>

    @Query("SELECT * FROM form_break_event WHERE sessionId = :id ORDER BY tOffsetMs ASC")
    abstract suspend fun getEvents(id: Long): List<FormBreakEventEntity>

    // ─────────────────────────────────────────────────────
    // 숨김 처리 (v3부터). 기존 getXxx는 hidden 포함 전체를 반환.
    // getVisibleXxx는 UI에 실제로 보이는 것만, getHiddenXxx는 배너에 나열할 것만.
    // ─────────────────────────────────────────────────────

    @Query("SELECT * FROM location_summary WHERE sessionId = :id AND isHidden = 0")
    abstract suspend fun getVisibleLocationSummaries(id: Long): List<LocationSummaryEntity>

    @Query("SELECT * FROM location_summary WHERE sessionId = :id AND isHidden = 1")
    abstract suspend fun getHiddenLocationSummaries(id: Long): List<LocationSummaryEntity>

    @Query("SELECT * FROM impact_sample WHERE sessionId = :id AND isHidden = 0 ORDER BY tOffsetMs ASC")
    abstract suspend fun getVisibleImpactSamples(id: Long): List<ImpactSampleEntity>

    @Query("SELECT * FROM form_break_event WHERE sessionId = :id AND isHidden = 0 ORDER BY tOffsetMs ASC")
    abstract suspend fun getVisibleEvents(id: Long): List<FormBreakEventEntity>

    @Query("SELECT * FROM form_break_event WHERE sessionId = :id AND isHidden = 1 ORDER BY tOffsetMs ASC")
    abstract suspend fun getHiddenEvents(id: Long): List<FormBreakEventEntity>

    // ── 부위 단위 숨김 토글 (impact_sample + location_summary를 함께 처리) ──

    @Query("UPDATE impact_sample SET isHidden = :hidden WHERE sessionId = :id AND location = :loc")
    abstract suspend fun setImpactSamplesHidden(id: Long, loc: JointLocation, hidden: Boolean)

    @Query("UPDATE location_summary SET isHidden = :hidden WHERE sessionId = :id AND location = :loc")
    abstract suspend fun setLocationSummaryHidden(id: Long, loc: JointLocation, hidden: Boolean)

    /** 부위 하나를 숨기거나 되살린다. 두 테이블을 원자적으로 처리. */
    @Transaction
    open suspend fun setJointHidden(id: Long, loc: JointLocation, hidden: Boolean) {
        setImpactSamplesHidden(id, loc, hidden)
        setLocationSummaryHidden(id, loc, hidden)
    }

    // ── 부위 단위 완전 삭제 ──

    @Query("DELETE FROM impact_sample WHERE sessionId = :id AND location = :loc")
    abstract suspend fun deleteImpactSamples(id: Long, loc: JointLocation)

    @Query("DELETE FROM location_summary WHERE sessionId = :id AND location = :loc")
    abstract suspend fun deleteLocationSummary(id: Long, loc: JointLocation)

    /** 부위 데이터 완전 삭제. 되돌릴 수 없음. */
    @Transaction
    open suspend fun deleteJoint(id: Long, loc: JointLocation) {
        deleteImpactSamples(id, loc)
        deleteLocationSummary(id, loc)
    }

    // ── 자세 무너짐 이벤트 개별 처리 ──

    @Query("UPDATE form_break_event SET isHidden = :hidden WHERE id = :eventId")
    abstract suspend fun setEventHidden(eventId: Long, hidden: Boolean)

    @Query("DELETE FROM form_break_event WHERE id = :eventId")
    abstract suspend fun deleteEvent(eventId: Long)

    @Query("DELETE FROM run_session WHERE sessionId = :id")
    abstract suspend fun deleteSession(id: Long)

    @Query("UPDATE run_session SET note = :note WHERE sessionId = :id")
    abstract suspend fun updateNote(id: Long, note: String)

    @Query("UPDATE run_session SET title = :title WHERE sessionId = :id")
    abstract suspend fun updateTitle(id: Long, title: String)

    @Query("SELECT COUNT(*) FROM run_session")
    abstract suspend fun count(): Int

    @Query("DELETE FROM run_session")
    abstract suspend fun deleteAll()
}

/* ─────────────────────────  Database  ───────────────────────── */

@Database(
    entities = [
        RunSessionEntity::class,
        RunSummaryEntity::class,
        LocationSummaryEntity::class,
        ImpactSampleEntity::class,
        EmgSampleEntity::class,
        FormBreakEventEntity::class,
        com.runtrack.data.db.FatigueSession::class,
        com.runtrack.data.db.MdfPoint::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(RunConverters::class)
abstract class RunDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun fatigueDao(): com.runtrack.data.db.FatigueDao

    companion object {
        const val NAME = "runtrack.db"

        /** v1 → v2: 근전도 측정 세션 테이블 추가 (기존 러닝 기록은 보존됨) */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS fatigue_session (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAtMs INTEGER NOT NULL,
                        preMdfLeftHz REAL NOT NULL,
                        preMdfRightHz REAL NOT NULL,
                        preRmsLeft REAL NOT NULL,
                        preRmsRight REAL NOT NULL,
                        preWindowCount INTEGER NOT NULL,
                        postMdfLeftHz REAL,
                        postMdfRightHz REAL,
                        postRmsLeft REAL,
                        postRmsRight REAL,
                        postWindowCount INTEGER,
                        postMeasuredAtMs INTEGER,
                        fiLeftPercent REAL,
                        fiRightPercent REAL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mdf_point (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        phase TEXT NOT NULL,
                        timeMs INTEGER NOT NULL,
                        mdfLeftHz REAL NOT NULL,
                        mdfRightHz REAL NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mdf_point_sessionId ON mdf_point(sessionId)")
            }
        }
        /** v2 → v3: 부위/이벤트별 숨김 플래그 컬럼 추가 (기본값 false) */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE impact_sample ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE location_summary ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE form_break_event ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
