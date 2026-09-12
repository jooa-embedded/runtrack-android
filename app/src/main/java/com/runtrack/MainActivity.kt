package com.runtrack

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.runtrack.data.datasource.BlePermissions
import com.runtrack.domain.UserPreferencesRepository
import com.runtrack.ui.navigation.RunTrackNavHost
import com.runtrack.ui.theme.RunTrackTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferencesRepository

    // 권한 허용 여부를 Compose가 관찰할 수 있도록 mutableState로
    var blePermissionsGranted by mutableStateOf(false)
        private set

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        blePermissionsGranted = result.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 진입 시 한 번 확인 후, 부족하면 요청
        blePermissionsGranted = hasAllBlePermissions()
        if (!blePermissionsGranted) {
            permissionLauncher.launch(BlePermissions.required)
        }

        setContent {
            RunTrackTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val onboardingComplete by userPreferences.onboardingComplete
                        .collectAsState(initial = null)
                    RunTrackNavHost(onboardingComplete = onboardingComplete)
                }
            }
        }
    }

    private fun hasAllBlePermissions(): Boolean =
        BlePermissions.required.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) ==
                    PackageManager.PERMISSION_GRANTED
        }

    fun requestBlePermissions() {
        permissionLauncher.launch(BlePermissions.required)
    }
}