package app.escriba.models

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
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

/** Tracks which models are on disk and drives their downloads. */
class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val workManager get() = WorkManager.getInstance(context)

    fun file(spec: ModelSpec): File = File(modelsDir, spec.localFileName)
    fun partialFile(spec: ModelSpec): File = File(modelsDir, spec.localFileName + ".part")

    fun isInstalled(spec: ModelSpec?): Boolean = spec != null && spec.id in _installed.value

    fun refresh() {
        _installed.value = scanInstalled()
    }

    private fun scanInstalled(): Set<String> = ModelCatalog.all
        .filter { spec -> File(modelsDir, spec.localFileName).let { it.isFile && it.length() == spec.sizeBytes } }
        .map { it.id }
        .toSet()

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
        file(spec).delete()
        partialFile(spec).delete()
        refresh()
    }

    /** Live download progress keyed by model id (only models with active or failed work). */
    val downloads: Flow<Map<String, DownloadState>> by lazy { workManager.getWorkInfosByTagFlow(TAG).map { infos ->
        val result = mutableMapOf<String, DownloadState>()
        // The most recent request per model wins.
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
    } }

    companion object {
        const val TAG = "model-download"
        private const val TAG_PREFIX = "model:"
        fun tagFor(id: String) = TAG_PREFIX + id
        fun uniqueName(id: String) = "download-$id"
    }
}
