package com.runtrack.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.runtrack.domain.UserPreferencesRepository
import com.runtrack.ui.components.PrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val name: String = "",
    val weight: String = "",
    val height: String = "",
    val saving: Boolean = false
) {
    val weightValue: Float? get() = weight.toFloatOrNull()
    val heightValue: Float? get() = height.toFloatOrNull()
    val canSubmit: Boolean
        get() = name.isNotBlank() && (weightValue?.let { it in 20f..250f } == true)
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val userPreferences: UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onWeight(v: String) = _state.update { it.copy(weight = v.filter { c -> c.isDigit() || c == '.' }) }
    fun onHeight(v: String) = _state.update { it.copy(height = v.filter { c -> c.isDigit() || c == '.' }) }

    fun submit(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSubmit || s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            userPreferences.completeOnboarding(
                name = s.name.trim(),
                weightKg = s.weightValue ?: 0f,
                heightCm = s.heightValue ?: 0f
            )
            onDone()
        }
    }
}

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            "런트랙에 오신 걸 환영해요",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "러닝 중 무릎·발목 충격과 근피로를 분석해 드릴게요. " +
                "먼저 간단한 프로필을 알려주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onName,
            label = { Text("이름") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.weight,
            onValueChange = viewModel::onWeight,
            label = { Text("몸무게 (kg) · 필수") },
            supportingText = { Text("충격량 분석에 사용됩니다") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.height,
            onValueChange = viewModel::onHeight,
            label = { Text("키 (cm) · 선택") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))
        PrimaryButton(
            text = if (state.saving) "저장 중..." else "시작하기",
            onClick = { viewModel.submit(onDone) },
            enabled = state.canSubmit && !state.saving,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(40.dp))
    }
}
