package com.runtrack.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.data.db.FatigueDao
import com.runtrack.data.db.FatigueSession
import com.runtrack.domain.RunRepository
import com.runtrack.domain.model.RunDetail
import com.runtrack.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs
import com.runtrack.domain.model.JointLocation

data class RunDetailUiState(
    val loading: Boolean = true,
    val detail: RunDetail? = null,
    val notFound: Boolean = false,
    /** 이 러닝과 시간상 매칭된 근전도 세션 (있으면 배너/결과 표시). */
    val fatigueSession: FatigueSession? = null
)

@HiltViewModel
class RunDetailViewModel @Inject constructor(
    private val repository: RunRepository,
    private val fatigueDao: FatigueDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val sessionId: Long = savedStateHandle.get<Long>(Routes.ARG_SESSION_ID) ?: -1L

    private val _state = MutableStateFlow(RunDetailUiState())
    val state: StateFlow<RunDetailUiState> = _state.asStateFlow()

    init {
        load()
        observeFatigue()
    }

    private fun load() {
        viewModelScope.launch { reloadDetail(initial = true) }
    }

    /** 상세를 다시 불러온다. 액션(숨김/삭제) 후 재계산 결과를 반영하기 위해 사용. */
    private suspend fun reloadDetail(initial: Boolean = false) {
        val detail = repository.getRunDetail(sessionId)
        _state.update {
            it.copy(
                loading = if (initial) false else it.loading,
                detail = detail,
                notFound = detail == null
            )
        }
    }

    /**
     * 이 러닝과 시간이 근접한 근전도 세션을 자동으로 매칭 (방법 A).
     * 러닝 시작 시각 기준 이전 30분 이내에 시작된 PRE 세션을 우선 선택.
     * DB가 변경될 때마다 자동 갱신.
     */
    private fun observeFatigue() {
        viewModelScope.launch {
            fatigueDao.sessions().collect { list ->
                val runStart = _state.value.detail?.session?.startedAt
                    ?: repository.getRunDetail(sessionId)?.session?.startedAt
                    ?: return@collect

                val matched = list
                    .filter { it.startedAtMs <= runStart }                       // 러닝 시작 전에 측정한 PRE
                    .filter { runStart - it.startedAtMs <= WINDOW_MS }           // 30분 이내
                    .minByOrNull { abs(runStart - it.startedAtMs) }              // 가장 근접한 것

                _state.update { it.copy(fatigueSession = matched) }
            }
        }
    }

    fun updateNote(note: String) {
        val current = _state.value.detail ?: return
        viewModelScope.launch {
            repository.updateNote(sessionId, note)
            _state.update {
                it.copy(detail = current.copy(session = current.session.copy(note = note)))
            }
        }
    }

    // ─────────────────────────────────────────────────────
// 부위 (발목/무릎) 숨김 · 복구 · 완전 삭제
// ─────────────────────────────────────────────────────

    fun hideJoint(location: JointLocation) {
        viewModelScope.launch {
            repository.setJointHidden(sessionId, location, hidden = true)
            reloadDetail()
        }
    }

    fun restoreJoint(location: JointLocation) {
        viewModelScope.launch {
            repository.setJointHidden(sessionId, location, hidden = false)
            reloadDetail()
        }
    }

    fun deleteJoint(location: JointLocation) {
        viewModelScope.launch {
            repository.deleteJoint(sessionId, location)
            reloadDetail()
        }
    }

// ─────────────────────────────────────────────────────
// 자세 무너짐 이벤트 숨김 · 복구 · 완전 삭제
// ─────────────────────────────────────────────────────

    fun hideEvent(eventId: Long) {
        viewModelScope.launch {
            repository.setEventHidden(eventId, hidden = true)
            reloadDetail()
        }
    }

    fun restoreEvent(eventId: Long) {
        viewModelScope.launch {
            repository.setEventHidden(eventId, hidden = false)
            reloadDetail()
        }
    }

    fun deleteEvent(eventId: Long) {
        viewModelScope.launch {
            repository.deleteEvent(eventId)
            reloadDetail()
        }
    }

    fun updateTitle(title: String) {
        val current = _state.value.detail ?: return
        val trimmed = title.trim().ifBlank { return }
        viewModelScope.launch {
            repository.updateTitle(sessionId, trimmed)
            _state.update {
                it.copy(detail = current.copy(session = current.session.copy(title = trimmed)))
            }
        }
    }

    private companion object {
        /** 러닝 시작 시각과의 매칭 허용 범위 (30분) */
        const val WINDOW_MS = 30L * 60L * 1000L
    }
}