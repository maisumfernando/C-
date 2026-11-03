package com.fernandogodinho.teclado.updates

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fernandogodinho.teclado.BuildConfig
import com.fernandogodinho.teclado.R
import com.fernandogodinho.teclado.TecladoApp
import com.fernandogodinho.teclado.core.ProjectInfo
import com.fernandogodinho.teclado.core.SecureHttp
import com.fernandogodinho.teclado.models.ModelCatalog
import com.fernandogodinho.teclado.models.ModelSpec
import com.fernandogodinho.teclado.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppRelease(val versionName: String, val versionCode: Int, val notes: String)

data class UpdateStatus(
    val modelUpdates: List<Pair<ModelSpec, ModelSpec>> = emptyList(),
    val appRelease: AppRelease? = null,
    val checkedAt: Long = 0,
) {
    val appUpdateAvailable: Boolean get() = (appRelease?.versionCode ?: 0) > BuildConfig.VERSION_CODE
}

/**
 * Looks for newer models and app versions. It only *informs*: models are downloaded when the
 * user taps "Atualizar" (and verified by SHA-256), and the app is never installed silently;
 * the user is sent to the official website instead.
 */
class UpdateChecker(private val app: TecladoApp) {

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(3, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    /** Refreshes the catalog and release info. Safe to call from any thread. */
    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        app.models.refreshCatalog()
        val release = runCatching { parseRelease(SecureHttp.fetchText(ProjectInfo.VERSION_URL, 8 * 1024)) }
            .onFailure { Log.w(TAG, "release check failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        val status = UpdateStatus(
            modelUpdates = ModelCatalog.updatesFor(app.models.installed.value),
            appRelease = release ?: _status.value.appRelease,
            checkedAt = System.currentTimeMillis(),
        )
        _status.value = status
        status
    }

    /** Recomputes model updates after downloads or deletions, without network access. */
    fun recompute() {
        _status.value = _status.value.copy(modelUpdates = ModelCatalog.updatesFor(app.models.installed.value))
    }

    fun notifyIfNeeded(status: UpdateStatus) {
        if (status.modelUpdates.isEmpty() && !status.appUpdateAvailable) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = PendingIntent.getActivity(
            app, 1,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_MODELS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            status.appUpdateAvailable -> "Versão ${status.appRelease?.versionName} disponível no site oficial."
            else -> status.modelUpdates.joinToString { it.second.name } + " disponível."
        }
        val notification = NotificationCompat.Builder(app, TecladoApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (status.appUpdateAvailable) "Nova versão do teclado" else "Modelos de IA atualizados")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val WORK_NAME = "update-check"
        private const val NOTIFICATION_ID = 42

        /** Validates the small version document published with the website. */
        fun parseRelease(json: String): AppRelease {
            val o = JSONObject(json)
            val name = o.getString("versionName")
            require(Regex("^\\d{1,3}(\\.\\d{1,3}){1,2}$").matches(name)) { "versão inválida" }
            val code = o.getInt("versionCode")
            require(code in 1..2_000_000_000) { "código inválido" }
            val notes = o.optString("notes", "").take(500).filter { !it.isISOControl() || it == '\n' }
            return AppRelease(name, code, notes)
        }
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TecladoApp
        if (!app.settings.current.checkUpdates) return Result.success()
        val status = app.updates.check()
        app.updates.notifyIfNeeded(status)
        return Result.success()
    }
}
