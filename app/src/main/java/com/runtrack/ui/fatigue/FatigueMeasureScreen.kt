package com.runtrack.ui.fatigue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.EmgSide
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.fatigue.FatigueMeasurementViewModel.Phase
import com.runtrack.ui.fatigue.FatigueMeasurementViewModel.State

@Composable
fun FatigueMeasureScreen(
    onBack: () -> Unit,
    onViewLatestRun: () -> Unit,
    viewModel: FatigueMeasurementViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val pendingSessionId by viewModel.pendingSessionId.collectAsState()
    val emgStates by viewModel.emgConnectionStates.collectAsState()

    val leftConnected = emgStates[EmgSide.LEFT_THIGH] == ConnectionState.CONNECTED
    val rightConnected = emgStates[EmgSide.RIGHT_THIGH] == ConnectionState.CONNECTED
    val anyConnected = leftConnected || rightConnected

    Scaffold(
        topBar = { RunTrackTopBar(title = "근전도 측정", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SensorStatusCard(
                emgStates = emgStates,
                onRescan = viewModel::rescan
            )

            Text(
                "월싯 자세(무릎 90°)를 잡은 뒤 시작하세요. 안정화 5초 후 25초간 측정합니다.\n" +
                        "테스트 단계이므로 한쪽 모듈만 연결되어도 측정 가능합니다.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = state is State.Idle && anyConnected,
                    onClick = viewModel::startPre,
                ) { Text("운동 전 측정") }

                Button(
                    enabled = pendingSessionId != null && anyConnected &&
                            (state is State.Idle || state is State.PreDone),
                    onClick = viewModel::startPost,
                ) { Text("운동 후 측정") }
            }

            when (val s = state) {
                is State.Idle -> if (pendingSessionId != null) {
                    Text(
                        "운동 전 측정이 완료된 세션이 있어요. 러닝을 마쳤다면 [운동 후 측정]을 눌러주세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is State.Stabilizing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("자세 안정화 중… ${s.secondsLeft}초 (움직이지 마세요)")
                }

                is State.Measuring -> {
                    Text(
                        (if (s.phase == Phase.PRE) "운동 전" else "운동 후") +
                                " 측정 중 — 남은 시간 ${s.secondsLeft}초"
                    )
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("실시간 MDF", style = MaterialTheme.typography.titleSmall)
                            // 값이 있는 쪽만 라인 그리기 (없는 쪽은 빈 시리즈 → 자동 스킵)
                            FatigueLineChart(
                                series = buildList {
                                    if (s.liveLeft.isNotEmpty()) {
                                        add(FatigueChartSeries("좌", Color(0xFF1A7A70), s.liveLeft.map { it.mdfHz }))
                                    }
                                    if (s.liveRight.isNotEmpty()) {
                                        add(FatigueChartSeries("우", Color(0xFFD85A30), s.liveRight.map { it.mdfHz }))
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .padding(top = 8.dp),
                                yUnit = "Hz",
                            )
                        }
                    }
                }

                is State.PreDone -> Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("운동 전 측정 완료", style = MaterialTheme.typography.titleMedium)
                        val sideText = when {
                            s.hasLeft && s.hasRight -> "좌/우 양쪽"
                            s.hasLeft -> "왼쪽만"
                            s.hasRight -> "오른쪽만"
                            else -> "?"
                        }
                        Text("측정된 모듈: $sideText")
                        Text("이 값이 오늘의 기준선으로 저장됐어요.")
                        Text("러닝을 마친 뒤 이 화면에서 [운동 후 측정]을 눌러주세요.")
                        Button(onClick = onBack) { Text("홈으로 (러닝 하러 가기)") }
                    }
                }

                is State.Complete -> Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("측정 결과", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "피로 지수 FI   " +
                                    "좌 ${s.fiLeftPercent?.let { "%.1f%%".format(it) } ?: "—"}   " +
                                    "우 ${s.fiRightPercent?.let { "%.1f%%".format(it) } ?: "—"}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "RMS 변화   " +
                                    "좌 ${s.rmsChangeLeftPercent?.let { "%.1f%%".format(it) } ?: "—"}   " +
                                    "우 ${s.rmsChangeRightPercent?.let { "%.1f%%".format(it) } ?: "—"}"
                        )
                        Text(
                            "FI가 클수록 근육의 전기적 피로 신호가 큽니다. " +
                                    "절대 기준보다 본인 과거 기록과 비교해보세요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onViewLatestRun,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("러닝 + 근전도 총 결과 보러가기")
                        }
                        OutlinedButton(
                            onClick = { viewModel.cancel() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("새 측정") }
                    }
                }

                is State.Error -> Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("측정 실패", style = MaterialTheme.typography.titleMedium)
                        Text(s.message)
                        Button(onClick = { viewModel.cancel() }) { Text("다시 시도") }
                    }
                }
            }
        }
    }
}

/* ─────────────  센서 연결 상태 카드  ───────────── */

@Composable
private fun SensorStatusCard(
    emgStates: Map<EmgSide, ConnectionState>,
    onRescan: () -> Unit
) {
    val leftState = emgStates[EmgSide.LEFT_THIGH] ?: ConnectionState.DISCONNECTED
    val rightState = emgStates[EmgSide.RIGHT_THIGH] ?: ConnectionState.DISCONNECTED
    val leftConnected = leftState == ConnectionState.CONNECTED
    val rightConnected = rightState == ConnectionState.CONNECTED
    val bothConnected = leftConnected && rightConnected
    val anyConnected = leftConnected || rightConnected

    Card {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "센서 연결",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRescan) { Text("재검색") }
            }

            SensorStatusRow(label = "왼쪽 허벅지", state = leftState)
            SensorStatusRow(label = "오른쪽 허벅지", state = rightState)

            val hint = when {
                bothConnected -> "양쪽 모두 정상 연결됨"
                anyConnected -> "한쪽만 연결된 상태로도 측정 가능합니다 (테스트 단계)"
                else -> "근전도 모듈이 연결되지 않았습니다. 재검색을 눌러주세요."
            }
            val hintColor = when {
                bothConnected -> Color(0xFF16A34A)
                anyConnected -> Color(0xFFD97706)
                else -> Color(0xFFDC2626)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = hintColor)
        }
    }
}

@Composable
private fun SensorStatusRow(label: String, state: ConnectionState) {
    val (statusText, statusColor) = when (state) {
        ConnectionState.DISCONNECTED -> "연결 안 됨" to Color(0xFF9CA3AF)
        ConnectionState.SCANNING -> "검색 중" to Color(0xFFD97706)
        ConnectionState.CONNECTING -> "연결 중" to Color(0xFFD97706)
        ConnectionState.CONNECTED -> "연결됨" to Color(0xFF16A34A)
        ConnectionState.ERROR -> "연결 오류" to Color(0xFFDC2626)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            statusText,
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/* ─────────────  가벼운 선 그래프  ───────────── */

data class FatigueChartSeries(
    val label: String,
    val color: Color,
    val values: List<Double>,
)

@Composable
fun FatigueLineChart(
    series: List<FatigueChartSeries>,
    modifier: Modifier = Modifier,
    yUnit: String = "",
) {
    val all = series.flatMap { it.values }
    if (all.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("데이터 수집 중…", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val yMin = all.min() * 0.95
    val yMax = (all.max() * 1.05).coerceAtLeast(yMin + 1e-6)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val w = size.width
            val h = size.height
            val grid = Color(0x22000000)
            for (i in 0..2) {
                val y = h * i / 2f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            series.forEach { s ->
                if (s.values.size < 2) return@forEach
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val x = w * i / (s.values.size - 1).toFloat()
                    val y = (h - (v - yMin) / (yMax - yMin) * h).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = Stroke(width = 5f))
            }
        }
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(s.color)
                    )
                    Spacer(Modifier.width(4.dp))
                    val last = s.values.lastOrNull()
                    Text(
                        "${s.label} ${last?.let { "%.1f".format(it) } ?: "—"}$yUnit",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}