package com.runtrack.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.runtrack.domain.model.TelemetryFrame
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.MainActivity
import com.runtrack.domain.RunDataSource
import com.runtrack.domain.model.ConnectionState
import com.runtrack.domain.model.NodeStatus
import com.runtrack.domain.model.RunPhase
import com.runtrack.ui.components.ColorDot
import com.runtrack.ui.components.PrimaryButton
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.components.SecondaryButton
import com.runtrack.ui.theme.DangerRed
import com.runtrack.ui.theme.NeutralGray
import com.runtrack.ui.theme.PositiveGreen
import com.runtrack.ui.theme.WarningAmber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeviceConnectViewModel @Inject constructor(
    private val dataSource: RunDataSource
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = dataSource.connectionState
    val nodeStatus: StateFlow<List<NodeStatus>> = dataSource.nodeStatus

    /** 허브에서 흐르는 최근 원격 측정값 (연결만 되어도 흐름). 화면 벗어나면 5초 뒤 자동 해제. */
    val liveTelemetry: StateFlow<TelemetryFrame?> =
        dataSource.liveTelemetry
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun connect() {
        viewModelScope.launch { dataSource.scanAndConnect() }
    }

    /** 허브 재검색 */
    fun rescan() {
        viewModelScope.launch {
            dataSource.disconnect()
            dataSource.scanAndConnect()
        }
    }

    fun startSession(onStarted: () -> Unit) {
        viewModelScope.launch {
            dataSource.startSession()
            dataSource.setPhase(RunPhase.DURING_RUN)
            onStarted()
        }
    }
}

@Composable
fun DeviceConnectScreen(
    onBack: () -> Unit,
    onStartRun: () -> Unit,
    viewModel: DeviceConnectViewModel = hiltViewModel()
) {
    val hubConn by viewModel.connectionState.collectAsState()
    val nodes by viewModel.nodeStatus.collectAsState()
    val telemetry by viewModel.liveTelemetry.collectAsState()
    val activity = LocalContext.current as? MainActivity
    val permissionsOk = activity?.blePermissionsGranted ?: false

    val isScanning = hubConn == ConnectionState.SCANNING
    val isConnecting = hubConn == ConnectionState.CONNECTING
    val isConnected = hubConn == ConnectionState.CONNECTED
    val isIdle = hubConn == ConnectionState.DISCONNECTED
    val isError = hubConn == ConnectionState.ERROR

    // 근전도 노드는 표시하지 않음 (근전도 측정 화면에서 별도 관리)
    val displayNodes = nodes.filter { !it.label.contains("근전도") }

    Scaffold(
        topBar = { RunTrackTopBar(title = "기기 연결", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            // 허브 연결 상태 배너만 표시
            DeviceBanner(
                label = "허브",
                icon = Icons.Filled.Bluetooth,
                state = hubConn
            )

            Spacer(Modifier.height(20.dp))

            if (!permissionsOk && !isConnected) {
                InfoCard(
                    text = "블루투스 권한이 허용되지 않았습니다. 권한을 허용한 뒤 검색하세요.",
                    color = WarningAmber
                )
                Spacer(Modifier.height(12.dp))
            }

            when {
                // 아무것도 안 붙은 상태 → 검색
                isIdle || (isError && !isConnected) -> {
                    Text(
                        "허브를 켠 뒤 검색하세요. 근전도 모듈은 [운동 전·후 근전도 측정] 화면에서 연결합니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(
                        text = if (isError) "다시 검색" else "기기 검색",
                        onClick = viewModel::connect,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 스캔/연결 진행 중
                isScanning || isConnecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "허브를 찾고 있어요...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    SecondaryButton(
                        text = "다시 검색",
                        onClick = viewModel::rescan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 연결됨
                isConnected -> {
                    Text(
                        "센서 노드 상태",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(10.dp))
                    displayNodes.forEach { node ->
                        NodeRow(node, telemetry)
                        Spacer(Modifier.height(8.dp))
                    }

                    val onlineCount = displayNodes.count { it.isOnline }
                    val totalCount = displayNodes.size
                    val hasAnyOnline = onlineCount > 0

                    Spacer(Modifier.height(12.dp))

                    // 온라인 노드 요약
                    InfoCard(
                        text = when {
                            !hasAnyOnline -> "온라인 센서가 없습니다. 허브와 센서 연결을 확인하세요."
                            onlineCount < totalCount ->
                                "일부 센서만 연결됨 ($onlineCount/$totalCount). 가능한 항목만 분석됩니다."
                            else -> "모든 센서 정상 ($onlineCount/$totalCount)"
                        },
                        color = when {
                            !hasAnyOnline -> DangerRed
                            onlineCount < totalCount -> WarningAmber
                            else -> PositiveGreen
                        }
                    )

                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = "러닝 시작하기",
                        onClick = { viewModel.startSession(onStartRun) },
                        enabled = hasAnyOnline,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = "다시 검색",
                        onClick = viewModel::rescan,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 허브 연결 상태 배너 */
@Composable
private fun DeviceBanner(
    label: String,
    icon: ImageVector,
    state: ConnectionState,
    detail: String? = null
) {
    val (statusText, color) = when (state) {
        ConnectionState.DISCONNECTED -> "연결 안 됨" to NeutralGray
        ConnectionState.SCANNING -> "검색 중" to WarningAmber
        ConnectionState.CONNECTING -> "연결 중" to WarningAmber
        ConnectionState.CONNECTED -> "연결됨" to PositiveGreen
        ConnectionState.ERROR -> "연결 오류" to DangerRed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                statusText,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun InfoCard(text: String, color: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun NodeRow(node: NodeStatus, telemetry: TelemetryFrame?) {
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
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                ColorDot(color = if (node.isOnline) PositiveGreen else NeutralGray)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        node.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // 온라인이면 실시간 값, 아니면 오프라인 표시
                    val livePreview = if (node.isOnline) livePreviewFor(node, telemetry) else null
                    Text(
                        livePreview ?: if (node.isOnline) "온라인 (신호 대기 중)" else "오프라인",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (livePreview != null) PositiveGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 배터리 정보 미지원 (허브 프로토콜 확장 예정)
            Text(
                "—",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 노드의 nodeId를 기준으로 TelemetryFrame에서 해당 부위 값을 뽑아 표시 문자열로 만든다.
 * 매핑:
 *   HUB-NODE-1 왼발목  → pta[LEFT_ANKLE]
 *   HUB-NODE-2 오른발목 → pta[RIGHT_ANKLE]
 *   HUB-NODE-3 왼무릎  → pta[LEFT_KNEE]
 *   HUB-NODE-4 오른무릎 → pta[RIGHT_KNEE]
 *   HUB-NODE-5 허리    → 케이던스 · 상체 기울기
 */
private fun livePreviewFor(node: NodeStatus, t: TelemetryFrame?): String? {
    if (t == null) return null
    return when (node.nodeId) {
        "HUB-NODE-1" -> t.pta[com.runtrack.domain.model.JointLocation.LEFT_ANKLE]
            ?.let { "충격 %.2f g".format(it) }
        "HUB-NODE-2" -> t.pta[com.runtrack.domain.model.JointLocation.RIGHT_ANKLE]
            ?.let { "충격 %.2f g".format(it) }
        "HUB-NODE-3" -> t.pta[com.runtrack.domain.model.JointLocation.LEFT_KNEE]
            ?.let { "충격 %.2f g".format(it) }
        "HUB-NODE-4" -> t.pta[com.runtrack.domain.model.JointLocation.RIGHT_KNEE]
            ?.let { "충격 %.2f g".format(it) }
        "HUB-NODE-5" -> {
            val cad = t.cadenceSpm
            val pitch = t.trunkPitchDeg
            when {
                cad > 0 && pitch != null -> "케이던스 %d spm · 기울기 %.1f°".format(cad, pitch)
                pitch != null -> "기울기 %.1f°".format(pitch)
                cad > 0 -> "케이던스 %d spm".format(cad)
                else -> null
            }
        }
        else -> null
    }
}