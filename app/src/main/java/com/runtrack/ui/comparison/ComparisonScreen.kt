package com.runtrack.ui.comparison

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.runtrack.domain.model.Insight
import com.runtrack.domain.model.InsightSentiment
import com.runtrack.ui.charts.ChartSeries
import com.runtrack.ui.charts.LineChart
import com.runtrack.ui.components.Format
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.components.SectionHeader
import com.runtrack.ui.components.sentimentColor
import com.runtrack.ui.theme.NeutralGray
import com.runtrack.ui.theme.PositiveGreen
import com.runtrack.ui.theme.SeriesLeftKnee
import com.runtrack.ui.theme.SeriesRightKnee
import com.runtrack.ui.theme.WarningAmber
import kotlin.math.abs

@Composable
fun ComparisonScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    viewModel: ComparisonViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { RunTrackTopBar(
            title = "러닝 비교 · 분석",
            onBack = onBack,
            actions = {
                IconButton(onClick = onHome) {
                    Icon(Icons.Filled.Home, contentDescription = "홈으로")
                }
            }
        ) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null || state.current == null -> Text(
                    state.error ?: "기록을 찾을 수 없어요.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> ComparisonContent(state)
            }
        }
    }
}

@Composable
private fun ComparisonContent(state: ComparisonUiState) {
    val current = state.current!!.summary
    val baseline = state.baseline?.summary

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            if (baseline != null) "직전 러닝과 비교했어요"
            else "첫 러닝이라 비교 대상이 없어요",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (baseline != null) {
            Text(
                "${Format.date(state.baseline!!.session.startedAt)} → " +
                    Format.date(state.current.session.startedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── insights ──
        Spacer(Modifier.height(20.dp))
        SectionHeader(title = "분석 피드백")
        Spacer(Modifier.height(10.dp))
        state.insights.forEach { insight ->
            InsightCard(insight)
            Spacer(Modifier.height(10.dp))
        }

        // ── delta table ──
        if (baseline != null) {
            Spacer(Modifier.height(14.dp))
            SectionHeader(title = "지표 변화")
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(4.dp)) {
                    DeltaRow("무릎 평균 충격", baseline.avgKneePta, current.avgKneePta,
                        "g", lowerIsBetter = true)
                    DeltaRow("발목 평균 충격", baseline.avgAnklePta, current.avgAnklePta,
                        "g", lowerIsBetter = true)
                    DeltaRow("피로 지수", baseline.fatigueIndexPct, current.fatigueIndexPct,
                        "%", lowerIsBetter = true)
                    DeltaRow("충격 흡수율", baseline.avgKneeAttenuation * 100f,
                        current.avgKneeAttenuation * 100f, "%", lowerIsBetter = false)
                    DeltaRow("좌우 비대칭", baseline.asymmetryIndex, current.asymmetryIndex,
                        "%", lowerIsBetter = true)
                    DeltaRow("자세 무너짐", baseline.formBreakCount.toFloat(),
                        current.formBreakCount.toFloat(), "회", lowerIsBetter = true)
                }
            }
        }

        // ── trend charts ──
        if (state.kneeTrend.size >= 2) {
            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "무릎 충격 추이 (최근 ${state.kneeTrend.size}회)")
            Spacer(Modifier.height(10.dp))
            TrendCard {
                LineChart(
                    series = listOf(
                        ChartSeries("무릎 평균 PTA", SeriesLeftKnee, state.kneeTrend)
                    ),
                    yUnit = "g",
                    height = 150
                )
            }
            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "피로 지수 추이")
            Spacer(Modifier.height(10.dp))
            TrendCard {
                LineChart(
                    series = listOf(
                        ChartSeries("피로 지수", SeriesRightKnee, state.fatigueTrend)
                    ),
                    yUnit = "%",
                    height = 150
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TrendCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    val color = sentimentColor(insight.sentiment)
    val icon = when (insight.sentiment) {
        InsightSentiment.POSITIVE -> Icons.AutoMirrored.Filled.TrendingUp
        InsightSentiment.WARNING -> Icons.AutoMirrored.Filled.TrendingDown
        InsightSentiment.NEUTRAL -> Icons.AutoMirrored.Filled.TrendingFlat
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.10f)
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.18f))
                    .padding(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        insight.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        insight.deltaText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    insight.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DeltaRow(
    label: String,
    from: Float,
    to: Float,
    unit: String,
    lowerIsBetter: Boolean
) {
    val delta = to - from
    val improved = if (lowerIsBetter) delta < 0 else delta > 0
    val flat = abs(delta) < 0.05f
    val color: Color = when {
        flat -> NeutralGray
        improved -> PositiveGreen
        else -> WarningAmber
    }
    val arrow = when {
        flat -> "→"
        delta > 0 -> "▲"
        else -> "▼"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%.1f → %.1f%s".format(from, to, unit),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "  $arrow %.1f".format(abs(delta)),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
