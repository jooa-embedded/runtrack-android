package com.runtrack.ui.comparison

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.RunRepository
import com.runtrack.domain.analysis.RunComparisonEngine
import com.runtrack.domain.model.Insight
import com.runtrack.domain.model.RunWithSummary
import com.runtrack.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ComparisonUiState(
    val loading: Boolean = true,
    val current: RunWithSummary? = null,
    val baseline: RunWithSummary? = null,
    val insights: List<Insight> = emptyList(),
    /** oldest→newest knee PTA across recent runs, for the trend chart. */
    val kneeTrend: List<Float> = emptyList(),
    val fatigueTrend: List<Float> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class ComparisonViewModel @Inject constructor(
    private val repository: RunRepository,
    private val engine: RunComparisonEngine,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>(Routes.ARG_SESSION_ID) ?: -1L

    private val _state = MutableStateFlow(ComparisonUiState())
    val state: StateFlow<ComparisonUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val runs = repository.observeRuns().first()   // newest-first
            val index = runs.indexOfFirst { it.session.sessionId == sessionId }
            if (index < 0) {
                _state.update { it.copy(loading = false, error = "기록을 찾을 수 없어요.") }
                return@launch
            }
            val current = runs[index]
            val baseline = runs.getOrNull(index + 1)      // the run before it

            val insights = engine.compare(current.summary, baseline?.summary)

            // build trends from runs at-or-before the selected one, oldest→newest
            val trendRuns = runs.drop(index).take(10).reversed()
            _state.update {
                it.copy(
                    loading = false,
                    current = current,
                    baseline = baseline,
                    insights = insights,
                    kneeTrend = trendRuns.map { r -> r.summary.avgKneePta },
                    fatigueTrend = trendRuns.map { r -> r.summary.fatigueIndexPct }
                )
            }
        }
    }
}
