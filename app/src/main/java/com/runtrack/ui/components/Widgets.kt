package com.runtrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runtrack.domain.model.EventSeverity
import com.runtrack.domain.model.InsightSentiment
import com.runtrack.ui.theme.DangerRed
import com.runtrack.ui.theme.NeutralGray
import com.runtrack.ui.theme.PositiveGreen
import com.runtrack.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Primary filled action button, full-width by default. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** Secondary outlined action button. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Standard top app bar with an optional back button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunTrackTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge)
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "뒤로 가기"
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/** A small pill-shaped status chip. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Color helpers for semantic meaning. */
fun sentimentColor(sentiment: InsightSentiment): Color = when (sentiment) {
    InsightSentiment.POSITIVE -> PositiveGreen
    InsightSentiment.WARNING -> WarningAmber
    InsightSentiment.NEUTRAL -> NeutralGray
}

fun severityColor(severity: EventSeverity): Color = when (severity) {
    EventSeverity.LOW -> NeutralGray
    EventSeverity.MEDIUM -> WarningAmber
    EventSeverity.HIGH -> DangerRed
}

/* ─────────────────  Formatting helpers  ───────────────── */

object Format {
    private val dateFmt = SimpleDateFormat("M월 d일 (E)", Locale.KOREAN)
    private val timeFmt = SimpleDateFormat("a h:mm", Locale.KOREAN)

    fun date(epochMs: Long): String = dateFmt.format(Date(epochMs))
    fun time(epochMs: Long): String = timeFmt.format(Date(epochMs))
    fun dateTime(epochMs: Long): String = "${date(epochMs)} ${time(epochMs)}"

    fun duration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    fun durationKo(seconds: Int): String {
        val m = seconds / 60
        return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
    }

    fun distance(meters: Float?): String =
        if (meters == null) "—" else "%.2f km".format(meters / 1000f)

    fun g(value: Float): String = "%.1f".format(value)
    fun pct(value: Float): String = "%.0f%%".format(value)
    fun pct1(value: Float): String = "%.1f%%".format(value)
    fun hz(value: Float): String = "%.0f Hz".format(value)
}
