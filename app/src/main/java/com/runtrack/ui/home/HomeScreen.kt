package com.runtrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.RunRepository
import com.runtrack.domain.UserPreferencesRepository
import com.runtrack.domain.model.RunWithSummary
import com.runtrack.ui.charts.ChartSeries
import com.runtrack.ui.charts.LineChart
import com.runtrack.ui.components.Format
import com.runtrack.ui.components.MetricCard
import com.runtrack.ui.components.PrimaryButton
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.components.SectionHeader
import com.runtrack.ui.theme.SeriesLeftKnee
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val userName: String = "",
    val runs: List<RunWithSummary> = emptyList()
) {
    val latest: RunWithSummary? get() = runs.firstOrNull()
    val totalRuns: Int get() = runs.size
    val totalSeconds: Int get() = runs.sumOf { it.session.durationSec }
    val kneeTrend: List<Float>
        get() = runs.take(7).reversed().map { it.summary.avgKneePta }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: RunRepository,
    userPreferences: UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedSampleDataIfEmpty()
        }
        viewModelScope.launch {
            userPreferences.userName.collect { name ->
                _state.update { it.copy(userName = name) }
            }
        }
        viewModelScope.launch {
            repository.observeRuns().collect { runs ->
                _state.update { it.copy(loading = false, runs = runs) }
            }
        }
    }
}

@Composable
fun HomeScreen(
    onStartRun: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRun: (Long) -> Unit,
    onOpenFatigue: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            RunTrackTopBar(
                title = "런트랙",
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            val greetingName = state.userName.ifBlank { "러너" }
            Text(
                "안녕하세요, ${greetingName}님",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "오늘도 건강하게 달려볼까요?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            PrimaryButton(
                text = "러닝 시작",
                onClick = onStartRun,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenFatigue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("운동 전 · 후 근전도 측정")
            }
            Spacer(Modifier.height(24.dp))

            // ── totals ──
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    label = "총 러닝",
                    value = "${state.totalRuns}",
                    unit = "회",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "총 시간",
                    value = Format.durationKo(state.totalSeconds),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(24.dp))

            // ── latest run ──
            SectionHeader(
                title = "최근 러닝",
                actionLabel = "전체 보기",
                onAction = onOpenHistory
            )
            Spacer(Modifier.height(10.dp))
            val latest = state.latest
            if (latest == null) {
                EmptyHomeCard()
            } else {
                LatestRunCard(latest, onClick = { onOpenRun(latest.session.sessionId) })
            }

            // ── mini trend ──
            if (state.kneeTrend.size >= 2) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(title = "무릎 충격 추이 (최근 ${state.kneeTrend.size}회)")
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Box(Modifier.padding(14.dp)) {
                        LineChart(
                            series = listOf(
                                ChartSeries("무릎 평균 PTA", SeriesLeftKnee, state.kneeTrend)
                            ),
                            yUnit = "g",
                            height = 140
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun LatestRunCard(run: RunWithSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        run.session.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        Format.dateTime(run.session.startedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.DirectionsRun,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MiniStat("시간", Format.duration(run.session.durationSec))
                MiniStat("무릎 충격", "${Format.g(run.summary.avgKneePta)} g")
                MiniStat("피로 지수", Format.pct(run.summary.fatigueIndexPct))
                MiniStat("자세 무너짐", "${run.summary.formBreakCount}회")
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyHomeCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.DirectionsRun,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "아직 러닝 기록이 없어요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "첫 러닝을 시작해 보세요",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}