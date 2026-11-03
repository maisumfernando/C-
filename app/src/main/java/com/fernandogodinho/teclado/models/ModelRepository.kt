package com.fernandogodinho.teclado.models

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.fernandogodinho.teclado.core.ProjectInfo
import com.fernandogodinho.teclado.core.SecureHttp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File

data class DownloadState(
    val running: Boolean,
    val bytes: Long,
    val total: Long,
    val phase: Phase,
    val error: String? = null,
) {
    enum class Phase { QUEUED, DOWNLOADING, VERIFYING, FAILED }

    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Tracks which models are on disk and drives their downloads.
 *
 * A model counts as installed only if a verification marker written right after its SHA-256
 * check still matches the file's size and modification time, so a truncated or altered file is
 * never handed to the native parsers.
 */
class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val catalogFile = File(context.filesDir, "catalog.json")

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    /** Bumped whenever the set of known models changes (online catalog). */
    private val _catalogRevision = MutableStateFlow(0)
    val catalogRevision: StateFlow<Int> = _catalogRevision.asStateFlow()

    private val workManager get() = WorkManager.getInstance(context)

    init {
        loadStoredCatalog()
        _installed.value = scanInstalled()
    }

    fun specById(id: String?): ModelSpec? = ModelCatalog.byId(id)

    fun file(spec: ModelSpec): File = File(modelsDir, spec.localFileName)
    fun partialFile(spec: ModelSpec): File = File(modelsDir, spec.localFileName + ".part")
    private fun markerFile(spec: ModelSpec): File = File(modelsDir, spec.localFileName + ".verified")

    fun isInstalled(spec: ModelSpec?): Boolean = spec != null && spec.id in _installed.value

    fun refresh() {
        _installed.value = scanInstalled()
    }

    /** Records that [spec]'s file passed SHA-256 verification. */
    fun markVerified(spec: ModelSpec) {
        val f = file(spec)
        markerFile(spec).writeText("${spec.sha256} ${f.length()} ${f.lastModified()}")
    }

    fun isVerified(spec: ModelSpec): Boolean {
        val f = file(spec)
        if (!f.isFile || f.length() != spec.sizeBytes) return false
        val marker = markerFile(spec).takeIf { it.isFile }?.readText()?.trim() ?: return false
        return marker == "${spec.sha256} ${f.length()} ${f.lastModified()}"
    }

    private fun scanInstalled(): Set<String> = ModelCatalog.all.filter { isVerified(it) }.map { it.id }.toSet()

    fun download(spec: ModelSpec) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to spec.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .addTag(tagFor(spec.id))
            .build()
        workManager.enqueueUniqueWork(uniqueName(spec.id), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(spec: ModelSpec) {
        workManager.cancelUniqueWork(uniqueName(spec.id))
    }

    fun delete(spec: ModelSpec) {
        cancel(spec)
        file(spec).let { it.setWritable(true); it.delete() }
        partialFile(spec).delete()
        markerFile(spec).delete()
        refresh()
    }

    // ---------------------------------------------------------------------------------------
    // Online catalog (new and updated models)
    // ---------------------------------------------------------------------------------------

    private fun loadStoredCatalog() {
        val json = catalogFile.takeIf { it.isFile }?.readText() ?: return
        runCatching { ModelCatalog.setRemote(ModelCatalog.parseRemote(json).models) }
            .onFailure { catalogFile.delete() }
    }

    /**
     * Downloads and validates the online catalog. Returns the catalog version, or null if it
     * could not be fetched or failed validation (in which case nothing changes).
     * Must be called off the main thread.
     */
    fun refreshCatalog(): Int? {
        val json = runCatching { SecureHttp.fetchText(ProjectInfo.CATALOG_URL, 64 * 1024) }
            .onFailure { Log.w(TAG, "catalog fetch failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        val parsed = runCatching { ModelCatalog.parseRemote(json) }
            .onFailure { Log.w(TAG, "catalog rejected: ${it.message}") }
            .getOrNull() ?: return null
        // Never go back to an older catalog (protects against replaying stale data).
        val current = catalogFile.takeIf { it.isFile }?.let { runCatching { ModelCatalog.parseRemote(it.readText()).version }.getOrNull() }
        if (current != null && parsed.version < current) return current
        catalogFile.writeText(json)
        ModelCatalog.setRemote(parsed.models)
        _catalogRevision.value = _catalogRevision.value + 1
        refresh()
        return parsed.version
    }

    /** Live download progress keyed by model id (only models with active or failed work). */
    val downloads: Flow<Map<String, DownloadState>> by lazy {
        workManager.getWorkInfosByTagFlow(TAG).map { infos ->
            val result = mutableMapOf<String, DownloadState>()
            for (info in infos) {
                val id = info.tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX) ?: continue
                val spec = ModelCatalog.byId(id) ?: continue
                val state = when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState(
                        running = true,
                        bytes = partialFile(spec).length(),
                        total = spec.sizeBytes,
                        phase = DownloadState.Phase.QUEUED,
                    )
                    WorkInfo.State.RUNNING -> {
                        val p = info.progress
                        DownloadState(
                            running = true,
                            bytes = p.getLong(ModelDownloadWorker.KEY_BYTES, partialFile(spec).length()),
                            total = p.getLong(ModelDownloadWorker.KEY_TOTAL, spec.sizeBytes),
                            phase = if (p.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false)) {
                                DownloadState.Phase.VERIFYING
                            } else {
                                DownloadState.Phase.DOWNLOADING
                            },
                        )
                    }
                    WorkInfo.State.FAILED -> DownloadState(
                        running = false,
                        bytes = partialFile(spec).length(),
                        total = spec.sizeBytes,
                        phase = DownloadState.Phase.FAILED,
                        error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR),
                    )
                    else -> null
                }
                // An active request always wins over an older failed one.
                if (state != null && result[id]?.running != true) result[id] = state
            }
            result
        }
    }

    companion object {
        const val TAG = "model-download"
        private const val TAG_PREFIX = "model:"
        fun tagFor(id: String) = TAG_PREFIX + id
        fun uniqueName(id: String) = "download-$id"
    }
}
