package com.runtrack.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.RunRepository
import com.runtrack.domain.model.RunWithSummary
import com.runtrack.ui.components.ColorDot
import com.runtrack.ui.components.EmptyState
import com.runtrack.ui.components.Format
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.theme.NeutralGray
import com.runtrack.ui.theme.PositiveGreen
import com.runtrack.ui.theme.WarningAmber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val runs: List<RunWithSummary> = emptyList()
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: RunRepository
) : ViewModel() {

    val state: StateFlow<HistoryUiState> =
        repository.observeRuns()
            .map { HistoryUiState(loading = false, runs = it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HistoryUiState()
            )

    fun delete(sessionId: Long) {
        viewModelScope.launch { repository.deleteRun(sessionId) }
    }
}

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenRun: (Long) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<RunWithSummary?>(null) }
    var selectedTab by remember { mutableStateOf(0) }  // 0=리스트, 1=달력

    Scaffold(
        topBar = { RunTrackTopBar(title = "러닝 기록", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 탭 (리스트 / 달력) ──
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("리스트") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("달력") }
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center)
                    )

                    // 달력 탭: 기록이 없어도 항상 달력 표시
                    selectedTab == 1 -> CalendarView(
                        runs = state.runs,
                        onOpenRun = onOpenRun
                    )

                    // 리스트 탭: 기록 없으면 EmptyState
                    state.runs.isEmpty() -> EmptyState(
                        icon = Icons.AutoMirrored.Filled.DirectionsRun,
                        title = "기록이 없어요",
                        message = "러닝을 완료하면 여기에 기록이 쌓입니다.",
                        modifier = Modifier.align(Alignment.Center)
                    )

                    else -> ListView(
                        runs = state.runs,
                        onOpenRun = onOpenRun,
                        onDeleteRequest = { pendingDelete = it }
                    )
                }
            }
        }
    }

    pendingDelete?.let { run ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("기록을 삭제할까요?") },
            text = {
                Text("${Format.date(run.session.startedAt)} 러닝 기록이 영구 삭제됩니다.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(run.session.sessionId)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            }
        )
    }
}

/* ─────────────────────────────────────────────────
   리스트 뷰 (기존 방식)
────────────────────────────────────────────────── */

@Composable
private fun ListView(
    runs: List<RunWithSummary>,
    onOpenRun: (Long) -> Unit,
    onDeleteRequest: (RunWithSummary) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp, vertical = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(runs, key = { it.session.sessionId }) { run ->
            HistoryRow(
                run = run,
                onClick = { onOpenRun(run.session.sessionId) },
                onDelete = { onDeleteRequest(run) }
            )
        }
    }
}

/* ─────────────────────────────────────────────────
   달력 뷰
────────────────────────────────────────────────── */

@Composable
private fun CalendarView(
    runs: List<RunWithSummary>,
    onOpenRun: (Long) -> Unit
) {
    // 러닝 날짜 → 그날의 러닝 목록 매핑
    val runsByDate = remember(runs) {
        runs.groupBy { run ->
            Instant.ofEpochMilli(run.session.startedAt)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
    }

    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 12.dp
        )
    ) {
        // ── 이달 요약 ──
        item {
            MonthlySummary(
                month = displayedMonth,
                runs = runs.filter { run ->
                    val date = Instant.ofEpochMilli(run.session.startedAt)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    YearMonth.from(date) == displayedMonth
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        // ── 달력 그리드 ──
        item {
            CalendarGrid(
                month = displayedMonth,
                runsByDate = runsByDate,
                selectedDate = selectedDate,
                onSelectDate = { selectedDate = if (selectedDate == it) null else it },
                onPrevMonth = { displayedMonth = displayedMonth.minusMonths(1); selectedDate = null },
                onNextMonth = { displayedMonth = displayedMonth.plusMonths(1); selectedDate = null }
            )
            Spacer(Modifier.height(16.dp))
        }

        // ── 선택된 날짜의 러닝 목록 (없으면 이달 전체) ──
        val displayRuns = if (selectedDate != null) {
            runsByDate[selectedDate] ?: emptyList()
        } else {
            runs.filter { run ->
                val date = Instant.ofEpochMilli(run.session.startedAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                YearMonth.from(date) == displayedMonth
            }
        }

        item {
            Text(
                text = when {
                    selectedDate != null -> "${selectedDate!!.format(DateTimeFormatter.ofPattern("M월 d일"))} 러닝"
                    else -> "이달 러닝 (${displayRuns.size}회)"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        if (displayRuns.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "이 기간에 러닝 기록이 없습니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(displayRuns, key = { it.session.sessionId }) { run ->
                Spacer(Modifier.height(8.dp))
                HistoryRow(
                    run = run,
                    onClick = { onOpenRun(run.session.sessionId) },
                    onDelete = { /* 달력 뷰에서는 삭제 안 함 */ },
                    showDelete = false
                )
            }
        }
    }
}

@Composable
private fun MonthlySummary(month: YearMonth, runs: List<RunWithSummary>) {
    val totalDuration = runs.sumOf { it.session.durationSec.toLong() }
    val totalRuns = runs.size
    val avgKnee = if (runs.isNotEmpty()) {
        runs.map { it.summary.avgKneePta }.average().toFloat()
    } else 0f

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryStat("러닝", "${totalRuns}회")
            SummaryStat("총 시간", Format.duration(totalDuration.toInt()))
            SummaryStat("평균 무릎", "${Format.g(avgKnee)}g")
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CalendarGrid(
    month: YearMonth,
    runsByDate: Map<LocalDate, List<RunWithSummary>>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // ── 월 헤더 ──
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "이전 달")
            }
            Text(
                text = "${month.year}. ${"%02d".format(month.monthValue)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "다음 달")
            }
        }

        // ── 요일 헤더 ──
        Row(Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { idx, day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        day,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = when (idx) {
                            0 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // ── 날짜 그리드 ──
        val firstDay = month.atDay(1)
        val startOffset = (firstDay.dayOfWeek.value % 7)  // 일요일=0
        val totalCells = startOffset + month.lengthOfMonth()
        val rows = (totalCells + 6) / 7  // 올림 나눗셈

        val today = LocalDate.now()

        for (rowIdx in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (colIdx in 0 until 7) {
                    val cellIdx = rowIdx * 7 + colIdx
                    val dayOfMonth = cellIdx - startOffset + 1

                    if (dayOfMonth in 1..month.lengthOfMonth()) {
                        val date = month.atDay(dayOfMonth)
                        val runCount = runsByDate[date]?.size ?: 0
                        DayCell(
                            day = dayOfMonth,
                            runCount = runCount,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            isSunday = colIdx == 0,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        // 빈 셀
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    runCount: Int,
    isToday: Boolean,
    isSelected: Boolean,
    isSunday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasRun = runCount > 0
    val bgColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        hasRun -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isSunday -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = textColor
            )
            if (hasRun && !isSelected) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(minOf(runCount, 3)) {
                        Box(
                            modifier = Modifier
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(2.dp)
                        ) {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/* ─────────────────────────────────────────────────
   러닝 행 (리스트/달력에서 공용 사용)
────────────────────────────────────────────────── */

@Composable
private fun HistoryRow(
    run: RunWithSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    showDelete: Boolean = true
) {
    val statusColor = when {
        run.summary.avgKneePta > 8.5f -> WarningAmber
        run.summary.formBreakCount > 4 -> WarningAmber
        else -> PositiveGreen
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorDot(color = statusColor, size = 12)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    run.session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    Format.dateTime(run.session.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RowStat("시간", Format.duration(run.session.durationSec))
                    RowStat("무릎", "${Format.g(run.summary.avgKneePta)}g")
                    RowStat("피로", Format.pct(run.summary.fatigueIndexPct))
                }
            }
            if (showDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "삭제",
                        tint = NeutralGray
                    )
                }
            }
        }
    }
}

@Composable
private fun RowStat(label: String, value: String) {
    Row {
        Text(
            "$label ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}