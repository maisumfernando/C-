package com.fernandogodinho.teclado.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fernandogodinho.teclado.R
import com.fernandogodinho.teclado.tecladoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Optional ("Avisar quando a TV pedir texto"): keeps the paired TVs connected while the phone is
 * on the same network. When a TV opens a text field, a notification offers to type on the phone.
 * The ongoing notification also has a "Digitar na TV" action.
 */
class TvLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing("Procurando a TV na rede…"), type)
        val tv = tecladoApp.tv
        tv.startListening()
        scope.launch {
            combine(tv.paired, tv.status) { paired, status ->
                val connected = paired.filter { status[it.name] == TvLinkStatus.CONNECTED }.map { it.name }
                when {
                    paired.isEmpty() -> "Nenhuma TV pareada"
                    connected.isNotEmpty() -> "Conectado: ${connected.joinToString()}"
                    else -> "Aguardando a TV na mesma rede Wi-Fi"
                }
            }.collect { text ->
                getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoing(text))
            }
        }
        scope.launch {
            tv.requests.collect { req -> if (!TvKeyboardActivity.visible) notifyRequest(req) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            tecladoApp.settings.setTvListen(false)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tecladoApp.tv.stopListening()
        scope.cancel()
        super.onDestroy()
    }

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(this, 1, TvKeyboardActivity.intent(this, null), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, TvLinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle("Teclado na TV")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Digitar na TV", open)
            .addAction(0, "Desligar", stop)
            .build()
    }

    private fun notifyRequest(req: TvTextRequest) {
        val open = PendingIntent.getActivity(
            this, 3, TvKeyboardActivity.intent(this, req.tvName, req.value),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = req.label.ifBlank { "uma caixa de texto" }.take(60)
        val n = NotificationCompat.Builder(this, CHANNEL_REQUEST)
            .setSmallIcon(R.drawable.ic_stat_tv)
            .setContentTitle("${req.tvName} quer texto")
            .setContentText("A TV abriu $what. Toque para digitar ou ditar pelo celular.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(REQUEST_ID, n)
    }

    companion object {
        const val CHANNEL_LINK = "tv_link"
        const val CHANNEL_REQUEST = "tv_request"
        private const val ONGOING_ID = 4101
        const val REQUEST_ID = 4102
        private const val ACTION_STOP = "com.fernandogodinho.teclado.tv.STOP"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LINK, "Conexão com a TV", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Mostra que o teclado está pronto para digitar na TV"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REQUEST, "TV pedindo texto", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Avisa quando a TV abre uma caixa de texto (busca do YouTube, Netflix…)"
                },
            )
        }

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TvLinkService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, TvLinkService::class.java))
    }
}

/** Restarts the TV listener after a reboot, if the user turned it on. */
class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.tecladoApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                if (app.settings.load().tvListen && app.tv.paired.value.isNotEmpty()) {
                    runCatching { TvLinkService.start(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
