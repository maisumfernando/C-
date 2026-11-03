package app.escriba

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.escriba.core.SettingsRepository
import app.escriba.core.VocabularyStore
import app.escriba.engine.EngineManager
import app.escriba.models.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application-wide singletons shared by the keyboard service and the settings app. */
class EscribaApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var models: ModelRepository
        private set
    lateinit var engines: EngineManager
        private set
    lateinit var learnedWords: VocabularyStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        models = ModelRepository(this)
        engines = EngineManager(this, settings, models, appScope)
        learnedWords = VocabularyStore(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Download de modelos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progresso do download dos modelos de IA"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}

val Context.escriba: EscribaApp get() = applicationContext as EscribaApp
