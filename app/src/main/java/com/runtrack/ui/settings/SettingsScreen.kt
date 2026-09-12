package com.runtrack.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.RunRepository
import com.runtrack.domain.UserPreferencesRepository
import com.runtrack.ui.components.PrimaryButton
import com.runtrack.ui.components.RunTrackTopBar
import com.runtrack.ui.components.SecondaryButton
import com.runtrack.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val name: String = "",
    val weight: String = "",
    val height: String = "",
    val telemetryHz: Int = 5,
    val alertSound: Boolean = true,
    val alertHaptic: Boolean = true,
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferencesRepository,
    private val repository: RunRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                userPreferences.userName,
                userPreferences.weightKg,
                userPreferences.heightCm,
                userPreferences.telemetryHz
            ) { name, weight, height, hz ->
                listOf(name, weight, height, hz)
            }.collect { values ->
                _state.update {
                    it.copy(
                        name = values[0] as String,
                        weight = (values[1] as Float).takeIf { w -> w > 0f }
                            ?.let { w -> "%.0f".format(w) } ?: "",
                        height = (values[2] as Float).takeIf { h -> h > 0f }
                            ?.let { h -> "%.0f".format(h) } ?: "",
                        telemetryHz = values[3] as Int
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                userPreferences.alertSoundEnabled,
                userPreferences.alertHapticEnabled
            ) { sound, haptic -> sound to haptic }
                .collect { (sound, haptic) ->
                    _state.update { it.copy(alertSound = sound, alertHaptic = haptic) }
                }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onWeight(v: String) =
        _state.update { it.copy(weight = v.filter { c -> c.isDigit() || c == '.' }) }
    fun onHeight(v: String) =
        _state.update { it.copy(height = v.filter { c -> c.isDigit() || c == '.' }) }

    fun saveProfile() {
        val s = _state.value
        viewModelScope.launch {
            userPreferences.updateProfile(
                name = s.name.trim(),
                weightKg = s.weight.toFloatOrNull() ?: 0f,
                heightCm = s.height.toFloatOrNull() ?: 0f
            )
            _state.update { it.copy(message = "프로필이 저장되었습니다.") }
        }
    }

    fun setTelemetryHz(hz: Int) {
        viewModelScope.launch { userPreferences.setTelemetryHz(hz) }
    }

    fun setAlertSound(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setAlertSound(enabled) }
    }

    fun setAlertHaptic(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setAlertHaptic(enabled) }
    }

    fun regenerateSampleData() {
        viewModelScope.launch {
            repository.clearAllRuns()
            repository.seedSampleDataIfEmpty()
            _state.update { it.copy(message = "샘플 데이터를 다시 생성했습니다.") }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            repository.clearAllRuns()
            _state.update { it.copy(message = "모든 러닝 기록을 삭제했습니다.") }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { RunTrackTopBar(title = "설정", onBack = onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            SectionHeader(title = "프로필")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onName,
                label = { Text("이름") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.weight,
                    onValueChange = viewModel::onWeight,
                    label = { Text("몸무게 (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.height,
                    onValueChange = viewModel::onHeight,
                    label = { Text("키 (cm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = "프로필 저장",
                onClick = viewModel::saveProfile,
                modifier = Modifier.fillMaxWidth()
            )

            // ── telemetry ──
            Spacer(Modifier.height(28.dp))
            SectionHeader(title = "텔레메트리 표시 주기")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(5, 10).forEach { hz ->
                    FilterChip(
                        selected = state.telemetryHz == hz,
                        onClick = { viewModel.setTelemetryHz(hz) },
                        label = { Text("$hz Hz") }
                    )
                }
            }

            // ── alerts ──
            Spacer(Modifier.height(28.dp))
            SectionHeader(title = "자세 무너짐 알림")
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                label = "소리 알림",
                checked = state.alertSound,
                onChange = viewModel::setAlertSound
            )
            ToggleRow(
                label = "진동 알림",
                checked = state.alertHaptic,
                onChange = viewModel::setAlertHaptic
            )

            // ── data management ──
            Spacer(Modifier.height(28.dp))
            SectionHeader(title = "데이터 관리")
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "샘플 데이터 다시 생성",
                onClick = viewModel::regenerateSampleData,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "모든 기록 삭제",
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth()
            )

            // ── about ──
            Spacer(Modifier.height(28.dp))
            SectionHeader(title = "앱 정보")
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "런트랙 v0.1.0 (Phase 1)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "현재 버전은 목업 데이터로 동작합니다. " +
                            "센서 허브 연동은 다음 단계에서 추가됩니다.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // message dialog
    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            confirmButton = {
                TextButton(onClick = viewModel::consumeMessage) { Text("확인") }
            },
            text = { Text(msg) }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("모든 기록을 삭제할까요?") },
            text = { Text("저장된 모든 러닝 기록이 영구 삭제됩니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData()
                    confirmClear = false
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
