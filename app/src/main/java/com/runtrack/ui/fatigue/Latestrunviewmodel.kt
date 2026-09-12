package com.runtrack.ui.fatigue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.RunRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 근전도 측정 완료 후 [러닝+근전도 총 결과 보러가기] 버튼이 이용.
 * 가장 최근에 저장된 러닝 세션의 ID를 관찰해서 반환한다.
 */
@HiltViewModel
class LatestRunViewModel @Inject constructor(
    repository: RunRepository
) : ViewModel() {

    private val _latestRunId = MutableStateFlow<Long?>(null)
    val latestRunId: StateFlow<Long?> = _latestRunId.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeRuns().collect { runs ->
                _latestRunId.value = runs.firstOrNull()?.session?.sessionId
            }
        }
    }
}