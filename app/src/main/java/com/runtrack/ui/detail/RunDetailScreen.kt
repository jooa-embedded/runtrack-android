package com.runtrack.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.runtrack.data.db.FatigueSession
import com.runtrack.domain.model.FormBreakEvent
import com.runtrack.domain.model.ImpactSample
import com.runtrack.domain.model.JointLocation
import com.runtrack.domain.model.RunDetail
import com.runtrack.ui.charts.ChartMarker
import com.runtrack.ui.charts.ChartSeries
import com.runtrack.ui.charts.LineChart
import com.runtrack.ui.components.ColorDot
import com.runtrack.ui.components.Format
import com.runtrack.ui.components.MetricCard
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.components.SectionHeader
import com.runtrack.ui.components.severityColor
import com.runtrack.ui.theme.SeriesLeftAnkle
import com.runtrack.ui.theme.SeriesLeftKnee
import com.runtrack.ui.theme.SeriesRightAnkle
import com.runtrack.ui.theme.SeriesRightKnee

@Composable
fun RunDetailScreen(
    onBack: () -> Unit,
    onCompare: (Long) -> Unit,
    onHome: () -> Unit,
    onOpenFatigue: () -> Unit,
    viewModel: RunDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            RunTrackTopBar(
                title = "러닝 상세",
                onBack = onBack,
                actions = {
                    if (state.detail != null) {
                        IconButton(onClick = { onCompare(viewModel.sessionId) }) {
                            Icon(
                                Icons.Filled.CompareArrows,
                                contentDescription = "이전 러닝과 비교"
                            )
                        }
                        IconButton(onClick = onHome) {
                            Icon(Icons.Filled.Home, contentDescription = "홈으로")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.notFound || state.detail == null -> Text(
                    "기록을 찾을 수 없어요.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> DetailContent(
                    detail = state.detail!!,
                    fatigueSession = state.fatigueSession,
                    onOpenFatigue = onOpenFatigue,
                    onEditTitle = viewModel::updateTitle,
                    onEditNote = viewModel::updateNote,
                    onHideJoint = viewModel::hideJoint,
                    onRestoreJoint = viewModel::restoreJoint,
                    onDeleteJoint = viewModel::deleteJoint,
                    onHideEvent = viewModel::hideEvent,
                    onRestoreEvent = viewModel::restoreEvent,
                    onDeleteEvent = viewModel::deleteEvent,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    detail: RunDetail,
    fatigueSession: FatigueSession?,
    onOpenFatigue: () -> Unit,
    onEditTitle: (String) -> Unit,
    onEditNote: (String) -> Unit,
    onHideJoint: (JointLocation) -> Unit,
    onRestoreJoint: (JointLocation) -> Unit,
    onDeleteJoint: (JointLocation) -> Unit,
    onHideEvent: (Long) -> Unit,
    onRestoreEvent: (Long) -> Unit,
    onDeleteEvent: (Long) -> Unit,
) {
    val session = detail.session
    val summary = detail.summary

    // ── 로컬 UI 상태 ──
    var showTitleDialog by rememberSaveable { mutableStateOf(false) }
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var selectedJoint by remember { mutableStateOf<JointLocation?>(null) }
    var selectedEvent by remember { mutableStateOf<FormBreakEvent?>(null) }
    var confirmDeleteJoint by remember { mutableStateOf<JointLocation?>(null) }
    var confirmDeleteEvent by remember { mutableStateOf<FormBreakEvent?>(null) }
    var showHiddenSheet by rememberSaveable { mutableStateOf(false) }
    var helpToShow by remember { mutableStateOf<MetricHelp?>(null) }

    // 비대칭 지수: 좌·우 발목 중 하나라도 숨겨져 있으면 계산 불가라 "—" 처리
    val asymmetryHidden =
        JointLocation.LEFT_ANKLE in detail.hiddenLocations ||
                JointLocation.RIGHT_ANKLE in detail.hiddenLocations

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ── 제목 (편집 가능) ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showTitleDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "제목 편집")
            }
        }
        Text(
            Format.dateTime(session.startedAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── 숨김 항목 배너 (있을 때만) ──
        val hiddenCount = detail.hiddenLocations.size + detail.hiddenEvents.size
        if (hiddenCount > 0) {
            Spacer(Modifier.height(12.dp))
            HiddenItemsBanner(count = hiddenCount, onClick = { showHiddenSheet = true })
        }

        // ── 메모 카드 ──
        Spacer(Modifier.height(12.dp))
        NoteCard(note = session.note, onEditClick = { showNoteDialog = true })

        // ── 근전도 배너 / 결과 ──
        if (fatigueSession != null) {
            Spacer(Modifier.height(16.dp))
            if (fatigueSession.postMeasuredAtMs == null) {
                FatigueInviteBanner(onClick = onOpenFatigue)
            } else {
                FatigueResultCard(fatigueSession)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── headline metrics ──
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                label = "시간",
                value = Format.duration(session.durationSec),
                modifier = Modifier.weight(1f)
            )
            MetricCardWithHelp(
                help = MetricHelps.cadence,
                modifier = Modifier.weight(1f),
                onHelpClick = { helpToShow = it }
            ) {
                MetricCard(
                    label = "평균 케이던스",
                    value = "${summary.avgCadenceSpm}",
                    unit = "spm",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCardWithHelp(
                help = MetricHelps.kneeImpact,
                modifier = Modifier.weight(1f),
                onHelpClick = { helpToShow = it }
            ) {
                MetricCard(
                    label = "무릎 평균 충격",
                    value = Format.g(summary.avgKneePta),
                    unit = "g",
                    accent = SeriesLeftKnee,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            MetricCardWithHelp(
                help = MetricHelps.ankleImpact,
                modifier = Modifier.weight(1f),
                onHelpClick = { helpToShow = it }
            ) {
                MetricCard(
                    label = "발목 평균 충격",
                    value = Format.g(summary.avgAnklePta),
                    unit = "g",
                    accent = SeriesLeftAnkle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCardWithHelp(
                help = MetricHelps.fatigueIndex,
                modifier = Modifier.weight(1f),
                onHelpClick = { helpToShow = it }
            ) {
                MetricCard(
                    label = "피로 지수",
                    value = Format.pct(summary.fatigueIndexPct),
                    caption = "MDF ${Format.g(summary.mdfStartHz)}→${Format.g(summary.mdfEndHz)}Hz",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            MetricCardWithHelp(
                help = MetricHelps.asymmetry,
                modifier = Modifier.weight(1f),
                onHelpClick = { helpToShow = it }
            ) {
                MetricCard(
                    label = "좌우 비대칭",
                    value = when {
                        asymmetryHidden -> "—"
                        summary.asymmetryLeftHeavier == null -> Format.pct1(summary.asymmetryIndex)
                        summary.asymmetryLeftHeavier == true -> "왼쪽 +${Format.pct1(summary.asymmetryIndex)}"
                        else -> "오른쪽 +${Format.pct1(summary.asymmetryIndex)}"
                    },
                    caption = when {
                        asymmetryHidden -> "발목 데이터 일부 숨김"
                        summary.asymmetryLeftHeavier == true -> "왼쪽 발목에 더 실림"
                        summary.asymmetryLeftHeavier == false -> "오른쪽 발목에 더 실림"
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── 관절별 누적 충격량 (Impulse) ──
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(title = "관절별 누적 충격")
            HelpIcon(onClick = { helpToShow = MetricHelps.jointImpulse })
        }
        Spacer(Modifier.height(10.dp))
        val lastImpulseByJoint = JointLocation.entries.associateWith { joint ->
            detail.impactSamples
                .filter { it.location == joint }
                .maxByOrNull { it.tOffsetMs }
                ?.cumulativeImpulse ?: 0f
        }

        // 왼발목 / 오른발목
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JointMetricSlot(
                location = JointLocation.LEFT_ANKLE,
                hidden = JointLocation.LEFT_ANKLE in detail.hiddenLocations,
                modifier = Modifier.weight(1f),
                onLongPress = { selectedJoint = JointLocation.LEFT_ANKLE }
            ) {
                MetricCard(
                    label = "왼쪽 발목",
                    value = "%.0f".format(lastImpulseByJoint[JointLocation.LEFT_ANKLE] ?: 0f),
                    unit = "g·s",
                    accent = SeriesLeftAnkle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            JointMetricSlot(
                location = JointLocation.RIGHT_ANKLE,
                hidden = JointLocation.RIGHT_ANKLE in detail.hiddenLocations,
                modifier = Modifier.weight(1f),
                onLongPress = { selectedJoint = JointLocation.RIGHT_ANKLE }
            ) {
                MetricCard(
                    label = "오른쪽 발목",
                    value = "%.0f".format(lastImpulseByJoint[JointLocation.RIGHT_ANKLE] ?: 0f),
                    unit = "g·s",
                    accent = SeriesRightAnkle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // 왼무릎 / 오른무릎
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JointMetricSlot(
                location = JointLocation.LEFT_KNEE,
                hidden = JointLocation.LEFT_KNEE in detail.hiddenLocations,
                modifier = Modifier.weight(1f),
                onLongPress = { selectedJoint = JointLocation.LEFT_KNEE }
            ) {
                MetricCard(
                    label = "왼쪽 무릎",
                    value = "%.0f".format(lastImpulseByJoint[JointLocation.LEFT_KNEE] ?: 0f),
                    unit = "g·s",
                    accent = SeriesLeftKnee,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            JointMetricSlot(
                location = JointLocation.RIGHT_KNEE,
                hidden = JointLocation.RIGHT_KNEE in detail.hiddenLocations,
                modifier = Modifier.weight(1f),
                onLongPress = { selectedJoint = JointLocation.RIGHT_KNEE }
            ) {
                MetricCard(
                    label = "오른쪽 무릎",
                    value = "%.0f".format(lastImpulseByJoint[JointLocation.RIGHT_KNEE] ?: 0f),
                    unit = "g·s",
                    accent = SeriesRightKnee,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "누적 충격량은 러닝 동안 각 관절에 가해진 총 부하량입니다. 카드를 길게 눌러 숨기거나 삭제할 수 있어요.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── PTA timeline with event markers ──
        Spacer(Modifier.height(24.dp))
        SectionHeader(title = "충격량 타임라인")
        Spacer(Modifier.height(4.dp))
        Text(
            "점선 마커는 자세가 무너진 순간입니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        ChartCard {
            LineChart(
                series = jointSeries(detail.impactSamples),
                markers = eventMarkers(detail.events, session.durationSec),
                yUnit = "g",
                height = 200
            )
        }

        // ── shock attenuation ──
        Spacer(Modifier.height(20.dp))
        SectionHeader(title = "충격 흡수율 (무릎)")
        Spacer(Modifier.height(10.dp))
        ChartCard {
            LineChart(
                series = listOf(
                    ChartSeries(
                        "왼쪽 무릎",
                        SeriesLeftKnee,
                        detail.emgSamples.indices.map { i ->
                            summary.summaryFor(JointLocation.LEFT_KNEE)?.avgAttenuation ?: 0f
                        }
                    )
                ),
                minYOverride = 0f,
                maxYOverride = 1f,
                height = 120
            )
        }

        // ── muscle fatigue (MDF) ──
        Spacer(Modifier.height(20.dp))
        SectionHeader(title = "근피로 타임라인 (MDF)")
        Spacer(Modifier.height(10.dp))
        ChartCard {
            LineChart(
                series = listOf(
                    ChartSeries(
                        "MDF",
                        SeriesRightKnee,
                        detail.emgSamples.map { it.medianFreqHz }
                    )
                ),
                yUnit = "Hz",
                height = 160
            )
        }

        // ── RMS ──
        Spacer(Modifier.height(20.dp))
        SectionHeader(title = "근활성도 (RMS)")
        Spacer(Modifier.height(10.dp))
        ChartCard {
            LineChart(
                series = listOf(
                    ChartSeries(
                        "RMS",
                        SeriesRightAnkle,
                        detail.emgSamples.map { it.rms }
                    )
                ),
                height = 140
            )
        }

        // ── event list ──
        Spacer(Modifier.height(20.dp))
        SectionHeader(title = "자세 무너짐 이벤트 (${detail.events.size}회)")
        Spacer(Modifier.height(10.dp))
        if (detail.events.isEmpty()) {
            Text(
                "자세가 무너진 순간이 없었어요. 안정적인 러닝이었습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            detail.events.forEach { event ->
                EventRow(event, onLongPress = { selectedEvent = event })
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    // ─── 시트 & 다이얼로그 ───

    if (showTitleDialog) {
        EditTextDialog(
            title = "제목 편집",
            initialValue = session.title,
            singleLine = true,
            onDismiss = { showTitleDialog = false },
            onConfirm = {
                onEditTitle(it)
                showTitleDialog = false
            }
        )
    }
    if (showNoteDialog) {
        EditTextDialog(
            title = "메모 편집",
            initialValue = session.note,
            singleLine = false,
            placeholder = "이 러닝에 대한 메모를 남겨보세요 (컨디션, 코스, 날씨 등)",
            onDismiss = { showNoteDialog = false },
            onConfirm = {
                onEditNote(it)
                showNoteDialog = false
            }
        )
    }

    selectedJoint?.let { loc ->
        JointActionSheet(
            location = loc,
            isHidden = loc in detail.hiddenLocations,
            onDismiss = { selectedJoint = null },
            onHide = { onHideJoint(loc) },
            onRestore = { onRestoreJoint(loc) },
            onDeleteRequest = { confirmDeleteJoint = loc }
        )
    }
    selectedEvent?.let { evt ->
        EventActionSheet(
            event = evt,
            onDismiss = { selectedEvent = null },
            onHide = { onHideEvent(evt.id) },
            onDeleteRequest = { confirmDeleteEvent = evt }
        )
    }
    if (showHiddenSheet) {
        HiddenItemsSheet(
            detail = detail,
            onDismiss = { showHiddenSheet = false },
            onRestoreJoint = onRestoreJoint,
            onDeleteJoint = { confirmDeleteJoint = it },
            onRestoreEvent = onRestoreEvent,
            onDeleteEvent = { id ->
                detail.hiddenEvents.firstOrNull { it.id == id }
                    ?.let { confirmDeleteEvent = it }
            }
        )
    }
    confirmDeleteJoint?.let { loc ->
        ConfirmDeleteDialog(
            label = loc.displayName,
            onDismiss = { confirmDeleteJoint = null },
            onConfirm = { onDeleteJoint(loc) }
        )
    }
    confirmDeleteEvent?.let { evt ->
        ConfirmDeleteDialog(
            label = evt.type.displayName,
            onDismiss = { confirmDeleteEvent = null },
            onConfirm = { onDeleteEvent(evt.id) }
        )
    }
    helpToShow?.let { help ->
        MetricHelpDialog(help = help, onDismiss = { helpToShow = null })
    }
}

/* ─────────────  근전도 배너 / 결과  ───────────── */

@Composable
private fun FatigueInviteBanner(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "🎯 운동 후 근전도 측정하기",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "러닝 전에 측정한 근피로와 비교해서 변화를 확인할 수 있어요.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun FatigueResultCard(session: FatigueSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "근피로 분석 (운동 전 vs 후)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(0.4f))
                Text(
                    "왼쪽 허벅지",
                    modifier = Modifier.weight(0.3f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "오른쪽 허벅지",
                    modifier = Modifier.weight(0.3f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))

            FatigueMetricRow(
                label = "MDF 전",
                left = "%.1f Hz".format(session.preMdfLeftHz),
                right = "%.1f Hz".format(session.preMdfRightHz)
            )
            FatigueMetricRow(
                label = "MDF 후",
                left = session.postMdfLeftHz?.let { "%.1f Hz".format(it) } ?: "—",
                right = session.postMdfRightHz?.let { "%.1f Hz".format(it) } ?: "—"
            )

            Spacer(Modifier.height(8.dp))
            FatigueMetricRow(
                label = "피로 지수 FI",
                left = session.fiLeftPercent?.let { "%+.1f%%".format(it) } ?: "—",
                right = session.fiRightPercent?.let { "%+.1f%%".format(it) } ?: "—",
                emphasize = true
            )

            Spacer(Modifier.height(10.dp))
            Text(
                "FI가 클수록 근육의 전기적 피로 신호가 큽니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FatigueMetricRow(
    label: String,
    left: String,
    right: String,
    emphasize: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            left,
            modifier = Modifier.weight(0.3f),
            style = if (emphasize) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            right,
            modifier = Modifier.weight(0.3f),
            style = if (emphasize) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(event: FormBreakEvent, onLongPress: () -> Unit = {}) {
    val color = severityColor(event.severity)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
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
            ColorDot(color = color, size = 12)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    event.type.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    event.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                Format.duration((event.tOffsetMs / 1000L).toInt()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

/* ─────────────  chart data helpers  ───────────── */

private fun jointSeries(samples: List<ImpactSample>): List<ChartSeries> {
    val byJoint = samples.groupBy { it.location }
    fun series(joint: JointLocation, color: androidx.compose.ui.graphics.Color) =
        ChartSeries(
            label = joint.displayName,
            color = color,
            points = byJoint[joint]?.map { it.ptaAvgG } ?: emptyList()
        )
    return listOf(
        series(JointLocation.LEFT_KNEE, SeriesLeftKnee),
        series(JointLocation.RIGHT_KNEE, SeriesRightKnee),
        series(JointLocation.LEFT_ANKLE, SeriesLeftAnkle),
        series(JointLocation.RIGHT_ANKLE, SeriesRightAnkle)
    )
}

private fun eventMarkers(
    events: List<FormBreakEvent>,
    durationSec: Int
): List<ChartMarker> {
    val totalMs = (durationSec * 1000L).coerceAtLeast(1)
    return events.map { e ->
        ChartMarker(
            xFraction = (e.tOffsetMs.toFloat() / totalMs).coerceIn(0f, 1f),
            color = severityColor(e.severity),
            label = "자세 무너짐"
        )
    }
}

/* ─────────────────────────────────────────────────────
   제목/메모 편집
   ───────────────────────────────────────────────────── */

@Composable
private fun NoteCard(note: String, onEditClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onEditClick),
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
            Icon(
                Icons.Filled.EditNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "메모",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                if (note.isBlank()) {
                    Text(
                        "탭해서 메모를 추가하세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun EditTextDialog(
    title: String,
    initialValue: String,
    singleLine: Boolean,
    placeholder: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                placeholder = if (placeholder.isNotEmpty()) {
                    { Text(placeholder, style = MaterialTheme.typography.bodyMedium) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default
                ),
                minLines = if (singleLine) 1 else 3,
                maxLines = if (singleLine) 1 else 6,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = if (singleLine) text.trim().isNotEmpty() else true
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

/* ─────────────────────────────────────────────────────
   숨김 처리 UI
   ───────────────────────────────────────────────────── */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JointMetricSlot(
    location: JointLocation,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongPress
        )
    ) {
        if (hidden) {
            Box(Modifier.alpha(0.35f)) { content() }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "숨김",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            content()
        }
    }
}

@Composable
private fun HiddenItemsBanner(count: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "숨긴 항목 ${count}개 · 보기",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JointActionSheet(
    location: JointLocation,
    isHidden: Boolean,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onRestore: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                "${location.displayName} 데이터",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (isHidden) {
                ActionSheetItem(
                    icon = Icons.Filled.RestoreFromTrash,
                    title = "다시 보기",
                    subtitle = "결과와 요약 지표에 다시 포함됩니다.",
                    onClick = { onRestore(); onDismiss() }
                )
            } else {
                ActionSheetItem(
                    icon = Icons.Filled.VisibilityOff,
                    title = "이 결과에서 숨기기",
                    subtitle = "요약 지표에서 제외돼요. 언제든 되돌릴 수 있어요.",
                    onClick = { onHide(); onDismiss() }
                )
            }
            ActionSheetItem(
                icon = Icons.Filled.DeleteOutline,
                title = "완전 삭제",
                subtitle = "DB에서 지웁니다. 되돌릴 수 없어요.",
                tint = MaterialTheme.colorScheme.error,
                onClick = { onDeleteRequest(); onDismiss() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventActionSheet(
    event: FormBreakEvent,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                event.type.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            ActionSheetItem(
                icon = Icons.Filled.VisibilityOff,
                title = "이 이벤트 숨기기",
                subtitle = "요약과 타임라인에서 제외돼요. 되돌릴 수 있어요.",
                onClick = { onHide(); onDismiss() }
            )
            ActionSheetItem(
                icon = Icons.Filled.DeleteOutline,
                title = "완전 삭제",
                subtitle = "DB에서 지웁니다. 되돌릴 수 없어요.",
                tint = MaterialTheme.colorScheme.error,
                onClick = { onDeleteRequest(); onDismiss() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenItemsSheet(
    detail: RunDetail,
    onDismiss: () -> Unit,
    onRestoreJoint: (JointLocation) -> Unit,
    onDeleteJoint: (JointLocation) -> Unit,
    onRestoreEvent: (Long) -> Unit,
    onDeleteEvent: (Long) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("숨긴 항목", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (detail.hiddenLocations.isNotEmpty()) {
                Text(
                    "부위 (${detail.hiddenLocations.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                detail.hiddenLocations.forEach { loc ->
                    HiddenItemRow(
                        label = loc.displayName,
                        onRestore = { onRestoreJoint(loc) },
                        onDelete = { onDeleteJoint(loc) }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            if (detail.hiddenEvents.isNotEmpty()) {
                Text(
                    "자세 이벤트 (${detail.hiddenEvents.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                detail.hiddenEvents.forEach { evt ->
                    HiddenItemRow(
                        label = "${evt.type.displayName} · ${Format.duration((evt.tOffsetMs / 1000).toInt())}",
                        onRestore = { onRestoreEvent(evt.id) },
                        onDelete = { onDeleteEvent(evt.id) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HiddenItemRow(
    label: String,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onRestore) {
            Icon(
                Icons.Filled.RestoreFromTrash,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("복구")
        }
        TextButton(onClick = onDelete) {
            Text("삭제", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ActionSheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConfirmDeleteDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WarningAmber, contentDescription = null) },
        title = { Text("완전 삭제") },
        text = {
            Text("${label} 데이터를 완전히 삭제할까요?\nDB에서 지워지며 되돌릴 수 없어요.")
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/* ─────────────────────────────────────────────────────
   지표 도움말 (Metric Help)
   ───────────────────────────────────────────────────── */

private data class MetricHelp(
    val title: String,
    val description: String,
)

private val TierGood = Color(0xFF16A34A)
private val TierOk   = Color(0xFFEAB308)
private val TierWarn = Color(0xFFF97316)
private val TierBad  = Color(0xFFDC2626)

private object MetricHelps {
    val cadence = MetricHelp(
        title = "평균 케이던스",
        description = "1분 동안 발이 땅에 닿는 횟수예요. " +
                "단위 spm은 'steps per minute (분당 걸음 수)'을 뜻합니다.\n\n" +
                "이 카드에는 이번 러닝 전체의 평균 케이던스가 표시됩니다."
    )

    val ankleImpact = MetricHelp(
        title = "발목 평균 충격",
        description = "러닝 중 발이 땅에 닿는 순간 발목·정강이에 전달되는 " +
                "충격의 세기(가속도)의 평균이에요. 단위 g는 중력가속도의 배수로, " +
                "1g는 우리 몸무게가 정지 상태에서 받는 힘 정도라고 생각하면 됩니다. " +
                "예를 들어 4g는 그 4배의 순간 힘이 걸린 것.\n\n" +
                "이 카드에는 왼쪽과 오른쪽 발목의 평균값이 표시됩니다."
    )

    val kneeImpact = MetricHelp(
        title = "무릎 평균 충격",
        description = "무릎에 전달되는 충격의 세기(가속도)의 평균이에요. " +
                "단위 g는 중력가속도의 배수입니다.\n\n" +
                "발이 땅에 닿을 때 발생한 충격은 발목→무릎→허리로 올라오면서 " +
                "점점 흡수됩니다. 그래서 무릎 값은 보통 발목 값보다 작아요.\n\n" +
                "이 카드에는 왼쪽과 오른쪽 무릎의 평균값이 표시됩니다."
    )

    val fatigueIndex = MetricHelp(
        title = "피로 지수 (FI %)",
        description = "근전도(EMG) 신호를 분석해서 러닝 동안 근육이 얼마나 " +
                "지쳤는지를 %로 나타낸 값이에요.\n\n" +
                "근육이 피로해지면 근전도 신호의 주파수 특성(중앙주파수, MDF)이 " +
                "낮아지는데, 러닝 시작 시점 대비 마지막에 얼마나 낮아졌는지를 " +
                "%로 계산합니다.\n\n" +
                "값이 클수록 근피로가 크다는 의미입니다."
    )

    val asymmetry = MetricHelp(
        title = "좌우 비대칭",
        description = "왼쪽과 오른쪽 발목이 받는 평균 충격이 얼마나 차이 나는지를 " +
                "%로 표시한 값이에요.\n\n" +
                "0%에 가까울수록 좌·우가 균형 잡혀 있다는 뜻이고, " +
                "값이 클수록 한쪽에 더 큰 부담이 실렸다는 뜻입니다. " +
                "카드에는 어느 쪽이 더 많이 실렸는지(왼쪽/오른쪽)도 함께 표시됩니다."
    )

    val jointImpulse = MetricHelp(
        title = "관절별 누적 충격",
        description = "러닝 동안 각 관절에 가해진 충격을 시간에 걸쳐 누적한 값이에요. " +
                "단위 g·s는 '평균 g × 시간(초)'을 뜻합니다.\n\n" +
                "예: 100초 동안 매 초 평균 3g의 충격이 있었다면 300 g·s가 됩니다. " +
                "러닝 시간이 다르면 값도 자연히 커지기 때문에 절대값 비교보다는 " +
                "같은 세션 안에서 왼·오른쪽이나 발목·무릎 간 균형을 보는 데 유용합니다."
    )
}

@Composable
private fun MetricHelpDialog(help: MetricHelp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(help.title) },
        text = {
            Text(
                help.description,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}

@Composable
private fun MetricCardWithHelp(
    help: MetricHelp,
    modifier: Modifier = Modifier,
    onHelpClick: (MetricHelp) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        content()
        IconButton(
            onClick = { onHelpClick(help) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(30.dp)
                .padding(2.dp)
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "${help.title} 도움말",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HelpIcon(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(30.dp)
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = "도움말",
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}