package com.runtrack.ui.fatigue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runtrack.ui.charts.ChartSeries
import com.runtrack.ui.charts.LineChart
import androidx.hilt.navigation.compose.hiltViewModel
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.EmgSide
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.fatigue.IntermittentFatigueMeasurementViewModel.Phase
import com.runtrack.ui.fatigue.IntermittentFatigueMeasurementViewModel.Stage
import com.runtrack.ui.fatigue.IntermittentFatigueMeasurementViewModel.State

/**
 * 간헐 수축(Intermittent Contraction) 방식 근전도 측정 화면.
 * 총 62초, 2초 힘 주기 × 6회, 각 사이 8초 휴식.
 *
 * 팀에서 이 방식으로 되돌리기로 결정하면 NavHost의 FATIGUE 라우트를
 * FatigueMeasureScreen 대신 이 화면으로 연결한다.
 */
@Composable
fun IntermittentFatigueMeasureScreen(
    onBack: () -> Unit,
    onViewLatestRun: () -> Unit,
    viewModel: IntermittentFatigueMeasurementViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val pendingSessionId by viewModel.pendingSessionId.collectAsState()
    val emgStates by viewModel.emgConnectionStates.collectAsState()

    val leftConnected = emgStates[EmgSide.LEFT_THIGH] == ConnectionState.CONNECTED
    val rightConnected = emgStates[EmgSide.RIGHT_THIGH] == ConnectionState.CONNECTED
    val anyConnected = leftConnected || rightConnected

    Scaffold(
        topBar = { RunTrackTopBar(title = "근전도 측정 (간헐 수축)", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SensorStatusCard(
                emgStates = emgStates,
                onRescan = viewModel::rescan
            )

            Text(
                "총 60초 측정입니다.\n" +
                        "10초 주기로 6회, 각 5초씩 허벅지에 힘을 줍니다.\n" +
                        "화면 안내에 맞춰 힘을 주세요.\n" +
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

                is State.Running -> RunningCard(s)

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
                        ) { Text("러닝 + 근전도 총 결과 보러가기") }
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

/* ─────────────  센서 연결 상태 카드 (FatigueMeasureScreen과 동일)  ───────────── */

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

/* ─────────────  측정 중 큐 UI  ───────────── */

@Composable
private fun RunningCard(s: State.Running) {
    val (bgColor, mainText, subText) = when (s.stage) {
        Stage.PREPARE -> Triple(
            Color(0xFFE8F4F2),
            "준비하세요",
            "잠시 후 첫 번째 힘 주기가 시작됩니다"
        )
        Stage.CONTRACT -> Triple(
            Color(0xFFFEE2E2),
            "💪 힘 주세요!",
            "${s.contractionIndex}회차 / ${IntermittentFatigueMeasurementViewModel.CONTRACTION_COUNT}회"
        )
        Stage.REST -> Triple(
            Color(0xFFE0F2FE),
            "휴식",
            "다음 힘 주기까지 ${s.secondsLeftInStage}초"
        )
        Stage.COOLDOWN -> Triple(
            Color(0xFFE8F4F2),
            "마무리 중",
            "잠시 후 결과를 계산합니다"
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bgColor)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${s.secondsLeftInStage}",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    mainText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(subText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${if (s.phase == Phase.PRE) "운동 전" else "운동 후"} · " +
                            "전체 진행 ${s.totalElapsedSec}/${IntermittentFatigueMeasurementViewModel.TOTAL_SEC}초",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { s.totalElapsedSec / IntermittentFatigueMeasurementViewModel.TOTAL_SEC.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (1..IntermittentFatigueMeasurementViewModel.CONTRACTION_COUNT).forEach { idx ->
                        val done = idx < s.contractionIndex ||
                                (idx == s.contractionIndex && s.stage == Stage.REST)
                        val active = idx == s.contractionIndex && s.stage == Stage.CONTRACT
                        val color = when {
                            active -> Color(0xFFDC2626)
                            done -> Color(0xFF16A34A)
                            else -> Color(0xFFE5E7EB)
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$idx",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (color == Color(0xFFE5E7EB)) Color.Gray else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 실시간 근전도 활성도 그래프 (취득 확인용)
        // 실시간 근전도 활성도 그래프 (취득 확인용)
        if (s.previewLeft.isNotEmpty() || s.previewRight.isNotEmpty()) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("실시간 근활성도", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "힘을 주면 파형이 커지고, 쉬면 낮은 baseline으로 내려갑니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    LineChart(
                        series = listOfNotNull(
                            if (s.previewLeft.isNotEmpty()) ChartSeries(
                                label = "왼쪽",
                                color = Color(0xFF10B981),
                                points = s.previewLeft
                            ) else null,
                            if (s.previewRight.isNotEmpty()) ChartSeries(
                                label = "오른쪽",
                                color = Color(0xFFF59E0B),
                                points = s.previewRight
                            ) else null,
                        ),
                        height = 90
                    )
                }
            }
        }
    }
}