package app.escriba

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.escriba.core.DictationLanguage
import app.escriba.core.PolishStyle
import app.escriba.dictation.DictationPhase
import app.escriba.dictation.DictationSession
import app.escriba.dictation.DictationUiState
import app.escriba.ime.EscribaIme
import app.escriba.ime.KeyboardController
import app.escriba.ime.ShiftState
import app.escriba.ime.Suggestion
import app.escriba.ime.ui.KeyboardRoot
import app.escriba.ui.SetupState
import app.escriba.ui.screens.DictationSettingsScreen
import app.escriba.ui.screens.HomeScreen
import app.escriba.ui.screens.ModelsScreen
import app.escriba.ui.screens.OnboardingScreen
import app.escriba.ui.theme.EscribaTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/** Renders the main screens to build/screenshots for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/screenshots/$name.png")

    private fun keyboard(configure: (KeyboardController, EscribaApp) -> Unit = { _, _ -> }) {
        val service = Robolectric.buildService(EscribaIme::class.java).create().get()
        val controller = service.controller
        controller.onStartInput(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND
            },
        )
        configure(controller, service.application as EscribaApp)
        compose.setContent {
            Box(Modifier.background(Color(0xFF9A9A96))) { KeyboardRoot(service, controller) }
        }
        compose.waitForIdle()
    }

    private fun levels() = FloatArray(48) { i -> (0.25f + 0.6f * abs(sin(i * 0.45f)) * (i / 48f)).coerceIn(0f, 1f) }

    @Test
    fun keyboardLight() {
        keyboard { c, _ -> c.showPreview(null, listOf(Suggestion("voce", verbatim = true), Suggestion("você", autoCorrect = true), Suggestion("vocês")), ShiftState.ON) }
        shot("keyboard_light")
    }

    @Test
    fun keyboardDark() {
        RuntimeEnvironment.setQualifiers("+night")
        keyboard()
        shot("keyboard_dark")
    }

    @Test
    fun dictationListening() {
        keyboard { c, app ->
            val session = DictationSession(app, app.appScope, PolishStyle.FORMAL, DictationLanguage.PT, emptyList()) {}
            session.showPreviewState(
                DictationUiState(
                    phase = DictationPhase.LISTENING,
                    transcript = "é então tipo eu queria saber se você pode me mandar o relatório até sexta né porque o cliente pediu",
                    transcribing = true,
                    speaking = true,
                    elapsedMs = 14_000,
                ),
                levels(),
            )
            c.showPreview(session)
        }
        shot("dictation_listening")
    }

    @Test
    fun dictationPolishingDark() {
        RuntimeEnvironment.setQualifiers("+night")
        keyboard { c, app ->
            val session = DictationSession(app, app.appScope, PolishStyle.FORMAL, DictationLanguage.PT, emptyList()) {}
            session.showPreviewState(
                DictationUiState(
                    phase = DictationPhase.POLISHING,
                    transcript = "é então tipo eu queria saber se você pode me mandar o relatório até sexta né",
                    polished = "Gostaria de saber se você pode me enviar o relatório até sexta-feira, pois o cliente",
                ),
                FloatArray(48),
            )
            c.showPreview(session)
        }
        shot("dictation_polishing_dark")
    }

    @Test
    fun onboarding() {
        compose.setContent {
            EscribaTheme {
                OnboardingScreen(
                    setup = SetupState(enabled = true, selected = false, micGranted = false, speechModelReady = false, textModelReady = false),
                    onEnable = {}, onSelect = {}, onMic = {}, onModels = {}, onFinish = {},
                )
            }
        }
        shot("app_onboarding")
    }

    @Test
    fun home() {
        compose.setContent {
            EscribaTheme {
                HomeScreen(
                    setup = SetupState(enabled = true, selected = true, micGranted = true, speechModelReady = false, textModelReady = false),
                    navigate = {}, onEnable = {}, onSelect = {}, onMic = {},
                )
            }
        }
        shot("app_home")
    }

    @Test
    fun models() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
        compose.setContent { EscribaTheme { ModelsScreen(onBack = {}) } }
        shot("app_models")
    }

    @Test
    fun dictationSettingsDark() {
        RuntimeEnvironment.setQualifiers("+night")
        compose.setContent { EscribaTheme { DictationSettingsScreen(onBack = {}) } }
        shot("app_dictation_settings_dark")
    }
}
