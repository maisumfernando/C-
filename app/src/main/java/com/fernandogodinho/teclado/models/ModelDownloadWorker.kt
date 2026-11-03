package com.fernandogodinho.teclado.models

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fernandogodinho.teclado.TecladoApp
import com.fernandogodinho.teclado.R
import com.fernandogodinho.teclado.core.DeviceProfile
import com.fernandogodinho.teclado.core.SecureHttp
import com.fernandogodinho.teclado.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads one model with resume support and verifies its SHA-256 before it becomes usable.
 * Runs as a foreground task so multi-gigabyte downloads survive the app going to background.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as TecladoApp

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = app.models
        val spec = repo.specById(inputData.getString(KEY_MODEL_ID))
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Modelo desconhecido"))
        if (!SecureHttp.isAllowedModelUrl(spec.url)) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Origem do modelo não permitida"))
        }
        val target = repo.file(spec)
        if (repo.isVerified(spec)) {
            repo.refresh()
            return@withContext Result.success()
        }

        runCatching { setForeground(foregroundInfo(spec, 0, spec.sizeBytes, verifying = false)) }

        val part = repo.partialFile(spec)
        val needed = spec.sizeBytes - part.length() + 64L * 1024 * 1024
        if (DeviceProfile.freeStorageBytes(repo.modelsDir) < needed) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Espaço insuficiente no armazenamento"))
        }

        try {
            download(spec, part)
            setProgress(workDataOf(KEY_BYTES to spec.sizeBytes, KEY_TOTAL to spec.sizeBytes, KEY_VERIFYING to true))
            runCatching { setForeground(foregroundInfo(spec, spec.sizeBytes, spec.sizeBytes, verifying = true)) }
            val digest = sha256(part)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "Falha na verificação de integridade. Tente novamente."),
                )
            }
            if (!part.renameTo(target)) {
                throw IOException("Não foi possível salvar o modelo")
            }
            target.setReadOnly()
            repo.markVerified(spec)
            repo.refresh()
            onInstalled(spec)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 4 && e is IOException) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Erro de rede")))
            }
        }
    }

    private fun onInstalled(spec: ModelSpec) {
        val settings = app.settings
        when (spec.kind) {
            ModelKind.SPEECH -> if (!repoHasSelected(settings.current.speechModelId)) settings.setSpeechModel(spec.id)
            ModelKind.TEXT -> if (!repoHasSelected(settings.current.textModelId)) settings.setTextModel(spec.id)
        }
    }

    private fun repoHasSelected(id: String?): Boolean = app.models.isInstalled(app.models.specById(id))

    private suspend fun download(spec: ModelSpec, part: File) {
        var attempt = 0
        while (true) {
            try {
                downloadOnce(spec, part)
                return
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (++attempt >= 6) throw e
                kotlinx.coroutines.delay(1500L * attempt)
            }
        }
    }

    private suspend fun downloadOnce(spec: ModelSpec, part: File) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing > spec.sizeBytes) {
            part.delete()
            existing = 0L
        }
        if (existing == spec.sizeBytes) return

        val connection = SecureHttp.openModel(spec.url, existing)
        try {
            val code = connection.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                416 -> { // range not satisfiable: start over
                    part.delete()
                    throw IOException("Reiniciando download")
                }
                else -> throw IOException("Servidor respondeu $code")
            }
            if (!append) existing = 0L

            FileOutputStream(part, append).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 400) {
                            lastReport = now
                            setProgress(workDataOf(KEY_BYTES to written, KEY_TOTAL to spec.sizeBytes))
                            runCatching { setForeground(foregroundInfo(spec, written, spec.sizeBytes, false)) }
                        }
                    }
                    out.fd.sync()
                    if (written != spec.sizeBytes) {
                        throw IOException("Download incompleto ($written de ${spec.sizeBytes} bytes)")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foregroundInfo(spec: ModelSpec, bytes: Long, total: Long, verifying: Boolean): ForegroundInfo {
        val ctx = applicationContext
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
        val notification: Notification = NotificationCompat.Builder(ctx, TecladoApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (verifying) "Verificando ${spec.name}" else "Baixando ${spec.name}")
            .setContentText(if (verifying) "Conferindo a integridade do arquivo…" else "$percent% · ${formatBytes(bytes)} de ${formatBytes(total)}")
            .setProgress(100, percent, verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Cancelar", androidx.work.WorkManager.getInstance(ctx).createCancelPendingIntent(id))
            .build()
        val notificationId = 1000 + spec.id.hashCode().and(0xFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (gb >= 1) return "%.1f GB".format(java.util.Locale("pt", "BR"), gb)
    val mb = bytes / 1_000_000.0
    return "%.0f MB".format(java.util.Locale("pt", "BR"), mb)
}
