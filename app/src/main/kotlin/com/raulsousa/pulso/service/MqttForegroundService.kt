package com.raulsousa.pulso.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.raulsousa.pulso.MainActivity
import com.raulsousa.pulso.PulsoApplication
import com.raulsousa.pulso.R
import com.raulsousa.pulso.domain.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Mantém a sessão MQTT viva com o app fechado.
 *
 * Este é o substituto do `org.eclipse.paho.android.service.MqttService`, e a
 * diferença de fundo é quem manda: lá, a biblioteca era dona de um `Service`
 * com IPC próprio, o que quebrou quando o Android 8 restringiu execução em
 * background. Aqui o serviço é do app, é de primeiro plano (visível ao usuário,
 * como manda a política desde o Android 14) e o cliente MQTT é apenas uma
 * biblioteca comum que ele hospeda.
 *
 * O serviço só sobe quando o usuário pede nos ajustes. Manter socket aberto
 * custa bateria — essa é uma escolha do dono do aparelho, não do desenvolvedor.
 */
class MqttForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connection_connecting)))
        observeConnection()
        // START_STICKY: se o sistema matar por pressão de memória, volta sozinho.
        return START_STICKY
    }

    private fun observeConnection() {
        watcher?.cancel()
        val container = (application as PulsoApplication).container
        watcher = scope.launch {
            container.session.connection.collectLatest { state ->
                updateNotification(state.describe())
            }
        }
    }

    private fun ConnectionState.describe(): String = when (this) {
        is ConnectionState.Connected -> getString(R.string.connection_connected, brokerLabel)
        is ConnectionState.Connecting -> getString(R.string.connection_connecting)
        is ConnectionState.Reconnecting -> getString(R.string.connection_reconnecting, attempt)
        is ConnectionState.Failed -> getString(R.string.connection_failed, reason)
        ConnectionState.Disconnected -> getString(R.string.connection_disconnected)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MqttForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pulso.connection"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.raulsousa.pulso.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MqttForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MqttForegroundService::class.java))
        }
    }
}
