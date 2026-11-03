package com.fernandogodinho.teclado

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.fernandogodinho.teclado.core.SettingsRepository
import com.fernandogodinho.teclado.core.VocabularyStore
import com.fernandogodinho.teclado.engine.BenchmarkRunner
import com.fernandogodinho.teclado.engine.EngineManager
import com.fernandogodinho.teclado.models.ModelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.fernandogodinho.teclado.updates.UpdateChecker

/** Application-wide singletons shared by the keyboard service and the settings app. */
class TecladoApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var settings: SettingsRepository
        private set
    lateinit var models: ModelRepository
        private set
    lateinit var engines: EngineManager
        private set
    lateinit var learnedWords: VocabularyStore
        private set
    lateinit var updates: UpdateChecker
        private set
    /** On-device speed test of the installed models (created on first use). */
    val benchmark: BenchmarkRunner by lazy { BenchmarkRunner(this, engines, models, appScope) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        models = ModelRepository(this)
        engines = EngineManager(this, settings, models, appScope)
        learnedWords = VocabularyStore(this)
        updates = UpdateChecker(this)
        createChannels()
        appScope.launch {
            // Follow the user's choice for automatic update checks.
            settings.settings.map { it.checkUpdates }.distinctUntilChanged().collect { enabled ->
                runCatching { if (enabled) updates.schedulePeriodic() else updates.cancelPeriodic() }
            }
        }
        appScope.launch { models.installed.collect { updates.recompute() } }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Atualizações", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Novos modelos de IA e novas versões do teclado"
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Download de modelos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progresso do download dos modelos de IA"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_UPDATES = "updates"
    }
}

val Context.tecladoApp: TecladoApp get() = applicationContext as TecladoApp
