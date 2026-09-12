package com.runtrack.ui.liverun

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.runtrack.domain.model.JointLocation
import com.runtrack.ui.charts.ChartSeries
import com.runtrack.ui.charts.LineChart
import com.runtrack.ui.components.Format
import com.runtrack.ui.components.MetricCard
import com.runtrack.ui.components.PrimaryButton
import com.runtrack.ui.components.RatioGauge
import com.runtrack.ui.components.SectionHeader
import com.runtrack.ui.theme.Coral
import com.runtrack.ui.theme.SeriesLeftAnkle
import com.runtrack.ui.theme.SeriesLeftKnee
import com.runtrack.ui.theme.SeriesRightAnkle
import com.runtrack.ui.theme.SeriesRightKnee
import com.runtrack.ui.theme.WarningAmber

@Composable
fun LiveRunScreen(
    onFinished: (Long) -> Unit,
    onDiscard: () -> Unit,
    viewModel: LiveRunViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showStopDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    androidx.compose.runtime.LaunchedEffect(state.savedSessionId) {
        state.savedSessionId?.let(onFinished)
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // ── timer ──
            Text(
                "러닝 진행 중",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                Format.duration(state.elapsedSec),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "케이던스 ${state.cadenceSpm} spm · 신호품질 ${Format.pct(state.signalQuality * 100)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "누적 충격 ${"%.0f".format(state.totalImpulse)} g·s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── form-break alert ──
            AnimatedVisibility(visible = state.activeAlert != null) {
                state.activeAlert?.let { alert ->
                    Column {
                        Spacer(Modifier.height(14.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Coral.copy(alpha = 0.16f)
                            ),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = Coral
                                )
                                Spacer(Modifier.height(0.dp))
                                Text(
                                    "  ${alert.description}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // ── impact metrics ──
            SectionHeader(title = "관절 충격량 (PTA)")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    label = "왼쪽 발목",
                    value = Format.g(state.pta(JointLocation.LEFT_ANKLE)),
                    unit = "g",
                    accent = SeriesLeftAnkle,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "오른쪽 발목",
                    value = Format.g(state.pta(JointLocation.RIGHT_ANKLE)),
                    unit = "g",
                    accent = SeriesRightAnkle,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(
                    label = "왼쪽 무릎",
                    value = Format.g(state.pta(JointLocation.LEFT_KNEE)),
                    unit = "g",
                    accent = SeriesLeftKnee,
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "오른쪽 무릎",
                    value = Format.g(state.pta(JointLocation.RIGHT_KNEE)),
                    unit = "g",
                    accent = SeriesRightKnee,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── shock attenuation gauges ──
            SectionHeader(title = "무릎 충격 흡수율")
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                    RatioGauge(ratio = state.attenuationLeft, label = "왼쪽")
                    RatioGauge(ratio = state.attenuationRight, label = "오른쪽")
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── trunk posture (허리 자세) ──
            SectionHeader(title = "허리 자세")
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "앞뒤 기울기",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.trunkPitchDeg?.let { "%.1f°".format(it) } ?: "—",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "좌우 기울기",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                state.trunkRollDeg?.let { "%.1f°".format(it) } ?: "—",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── 알고리즘 분석 결과 (허브에서 수신, 없으면 숨김) ──
            if (state.impactScore != null || state.postureScore != null) {
                SectionHeader(title = "실시간 분석 결과")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(
                        label = "충격 점수",
                        value = state.impactScore?.let { "%.0f".format(it) } ?: "—",
                        unit = "/ 100",
                        caption = when (state.impactLevel) {
                            0 -> "정상"
                            1 -> "주의"
                            2 -> "높음"
                            else -> null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "자세 점수",
                        value = state.postureScore?.let { "%.0f".format(it) } ?: "—",
                        unit = "/ 100",
                        caption = if (state.postureCollapseHint != null && state.postureCollapseHint!! > 0)
                            "자세 무너짐 감지" else "정상",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(
                        label = "케이던스",
                        value = state.algorithmCadenceSpm?.let { "%.0f".format(it) } ?: "—",
                        unit = "spm",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "동적 RMS",
                        value = state.dynamicRmsG?.let { "%.2f".format(it) } ?: "—",
                        unit = "g",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── live PTA timeline ──
            SectionHeader(title = "실시간 충격 타임라인")
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Box(Modifier.padding(14.dp)) {
                    LineChart(
                        series = listOf(
                            ChartSeries("무릎 평균", SeriesLeftKnee, state.kneeHistory),
                            ChartSeries("발목 평균", SeriesLeftAnkle, state.ankleHistory)
                        ),
                        yUnit = "g",
                        height = 150
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = if (state.saving) "저장 중..." else "러닝 종료",
                onClick = { showStopDialog = true },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("러닝을 종료할까요?") },
            text = {
                Text(
                    if (state.elapsedSec < 10)
                        "기록이 너무 짧으면 저장하지 않고 종료됩니다."
                    else "이번 러닝이 기록으로 저장됩니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    if (state.elapsedSec < 10) viewModel.discard(onDiscard)
                    else viewModel.stopAndSave()
                }) { Text("종료") }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text("계속 달리기")
                }
            }
        )
    }
}