package com.runtrack.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.runtrack.domain.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : UserPreferencesRepository {

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val NAME = stringPreferencesKey("user_name")
        val WEIGHT = floatPreferencesKey("weight_kg")
        val HEIGHT = floatPreferencesKey("height_cm")
        val TELEMETRY_HZ = intPreferencesKey("telemetry_hz")
        val ALERT_SOUND = booleanPreferencesKey("alert_sound")
        val ALERT_HAPTIC = booleanPreferencesKey("alert_haptic")
    }

    private val data = context.dataStore.data

    override val onboardingComplete: Flow<Boolean> =
        data.map { it[Keys.ONBOARDING] ?: false }
    override val userName: Flow<String> =
        data.map { it[Keys.NAME] ?: "" }
    override val weightKg: Flow<Float> =
        data.map { it[Keys.WEIGHT] ?: 0f }
    override val heightCm: Flow<Float> =
        data.map { it[Keys.HEIGHT] ?: 0f }
    override val telemetryHz: Flow<Int> =
        data.map { it[Keys.TELEMETRY_HZ] ?: 5 }
    override val alertSoundEnabled: Flow<Boolean> =
        data.map { it[Keys.ALERT_SOUND] ?: true }
    override val alertHapticEnabled: Flow<Boolean> =
        data.map { it[Keys.ALERT_HAPTIC] ?: true }

    override suspend fun completeOnboarding(name: String, weightKg: Float, heightCm: Float) {
        context.dataStore.edit {
            it[Keys.NAME] = name
            it[Keys.WEIGHT] = weightKg
            it[Keys.HEIGHT] = heightCm
            it[Keys.ONBOARDING] = true
        }
    }

    override suspend fun updateProfile(name: String, weightKg: Float, heightCm: Float) {
        context.dataStore.edit {
            it[Keys.NAME] = name
            it[Keys.WEIGHT] = weightKg
            it[Keys.HEIGHT] = heightCm
        }
    }

    override suspend fun setTelemetryHz(hz: Int) {
        context.dataStore.edit { it[Keys.TELEMETRY_HZ] = hz }
    }

    override suspend fun setAlertSound(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALERT_SOUND] = enabled }
    }

    override suspend fun setAlertHaptic(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ALERT_HAPTIC] = enabled }
    }
}
