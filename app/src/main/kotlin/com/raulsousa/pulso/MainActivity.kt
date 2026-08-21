package com.raulsousa.pulso

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raulsousa.pulso.service.MqttForegroundService
import com.raulsousa.pulso.ui.PulsoApp
import com.raulsousa.pulso.ui.theme.PulsoTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Uma Activity só, Compose do começo ao fim.
 *
 * Compare com 2017: `AppCompatActivity` inflando XML, `findViewById`, callbacks
 * de MQTT tocando a View direto de outra thread e nenhuma noção de ciclo de
 * vida. Aqui a Activity não sabe o que é MQTT — ela hospeda a UI, cuida da
 * permissão de notificação e sai da frente.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        observeBackgroundPreference()

        setContent {
            PulsoTheme {
                NotificationPermissionGate()
                PulsoApp()
            }
        }
    }

    /**
     * O serviço em primeiro plano é ligado a partir daqui, e não do
     * `Application`: desde o Android 12 iniciar um FGS com o app em background
     * é bloqueado, e `Application.onCreate` pode rodar em background (num
     * `BroadcastReceiver`, por exemplo).
     */
    private fun observeBackgroundPreference() {
        val container = (application as PulsoApplication).container
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settingsRepository.settings
                    .map { it.keepAlive }
                    .distinctUntilChanged()
                    .collect { keepAlive ->
                        if (keepAlive) {
                            MqttForegroundService.start(this@MainActivity)
                        } else {
                            MqttForegroundService.stop(this@MainActivity)
                        }
                    }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun NotificationPermissionGate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { /* negar é legítimo: o app segue funcionando em primeiro plano */ },
        )
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
