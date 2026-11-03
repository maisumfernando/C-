package app.escriba.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.escriba.escriba
import app.escriba.ui.screens.AboutScreen
import app.escriba.ui.screens.DictationSettingsScreen
import app.escriba.ui.screens.HomeScreen
import app.escriba.ui.screens.KeyboardSettingsScreen
import app.escriba.ui.screens.ModelsScreen
import app.escriba.ui.screens.OnboardingScreen
import app.escriba.ui.screens.VocabularyScreen
import app.escriba.ui.theme.EscribaTheme

/** What the keyboard still needs before it can be used. */
data class SetupState(
    val enabled: Boolean,
    val selected: Boolean,
    val micGranted: Boolean,
    val speechModelReady: Boolean,
    val textModelReady: Boolean,
) {
    val keyboardReady: Boolean get() = enabled && selected
    val complete: Boolean get() = keyboardReady && micGranted && speechModelReady

    companion object {
        fun read(context: Context): SetupState {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val enabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            val app = context.escriba
            return SetupState(
                enabled = enabled,
                selected = current.startsWith(context.packageName + "/"),
                micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                speechModelReady = app.engines.selectedSpeechModel() != null,
                textModelReady = app.engines.selectedTextModel() != null,
            )
        }
    }
}

class MainActivity : ComponentActivity() {

    private var setup by mutableStateOf<SetupState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initialRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            val settings by escriba.settings.settings.collectAsState()
            val installed by escriba.models.installed.collectAsState()
            EscribaTheme(settings.theme) {
                // Re-evaluated on resume and whenever the installed models change.
                val current = remember(installed, setup) { SetupState.read(this@MainActivity) }
                var route by rememberSaveable { mutableStateOf(initialRoute ?: ROUTE_HOME) }
                val showOnboarding = !settings.onboardingDone && !current.complete
                if (showOnboarding && route == ROUTE_HOME) {
                    OnboardingScreen(
                        setup = current,
                        onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        onSelect = { showPicker() },
                        onMic = { requestMic() },
                        onModels = { route = ROUTE_MODELS },
                        onFinish = { escriba.settings.setOnboardingDone(true) },
                    )
                } else {
                    BackHandler(enabled = route != ROUTE_HOME) { route = ROUTE_HOME }
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            if (targetState == ROUTE_HOME) {
                                (slideInHorizontally(tween(260)) { -it / 5 } + fadeIn(tween(220))) togetherWith
                                    (slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(160)))
                            } else {
                                (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(220))) togetherWith
                                    (slideOutHorizontally(tween(260)) { -it / 5 } + fadeOut(tween(160)))
                            }
                        },
                        label = "route",
                    ) { r ->
                        Route(
                            route = r,
                            setup = current,
                            navigate = { route = it },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Route(route: String, setup: SetupState, navigate: (String) -> Unit) {
        val back = { navigate(ROUTE_HOME) }
        when (route) {
            ROUTE_MODELS -> ModelsScreen(onBack = back)
            ROUTE_DICTATION -> DictationSettingsScreen(onBack = back)
            ROUTE_KEYBOARD -> KeyboardSettingsScreen(onBack = back)
            ROUTE_VOCABULARY -> VocabularyScreen(onBack = back)
            ROUTE_ABOUT -> AboutScreen(onBack = back)
            else -> HomeScreen(
                setup = setup,
                navigate = navigate,
                onEnable = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                onSelect = { showPicker() },
                onMic = { requestMic() },
            )
        }
    }

    private fun showPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun requestMic() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input method picker is a system dialog: re-check when it closes.
        if (hasFocus) refresh()
    }

    private fun refresh() {
        escriba.models.refresh()
        setup = SetupState.read(this)
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val ROUTE_HOME = "home"
        const val ROUTE_MODELS = "models"
        const val ROUTE_DICTATION = "dictation"
        const val ROUTE_KEYBOARD = "keyboard"
        const val ROUTE_VOCABULARY = "vocabulary"
        const val ROUTE_ABOUT = "about"
    }
}
