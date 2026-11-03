package com.fernandogodinho.teclado

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fernandogodinho.teclado.core.DictationLanguage
import com.fernandogodinho.teclado.core.PolishStyle
import com.fernandogodinho.teclado.dictation.DictationMode
import com.fernandogodinho.teclado.dictation.DictationPhase
import com.fernandogodinho.teclado.dictation.DictationSession
import com.fernandogodinho.teclado.dictation.DictationUiState
import com.fernandogodinho.teclado.ime.TecladoIme
import com.fernandogodinho.teclado.ime.KeyboardController
import com.fernandogodinho.teclado.ime.ShiftState
import com.fernandogodinho.teclado.ime.Suggestion
import com.fernandogodinho.teclado.ime.ui.KeyboardRoot
import com.fernandogodinho.teclado.ui.SetupState
import com.fernandogodinho.teclado.ui.screens.DictationSettingsScreen
import com.fernandogodinho.teclado.ui.screens.HomeScreen
import com.fernandogodinho.teclado.ui.screens.ModelsScreen
import com.fernandogodinho.teclado.ui.screens.OnboardingScreen
import com.fernandogodinho.teclado.ui.theme.TecladoTheme
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

    private fun keyboard(configure: (KeyboardController, TecladoApp) -> Unit = { _, _ -> }) {
        val service = Robolectric.buildService(TecladoIme::class.java).create().get()
        val controller = service.controller
        controller.onStartInput(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND
            },
        )
        configure(controller, service.application as TecladoApp)
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
            val session = DictationSession(app, app.appScope, PolishStyle.FORMAL, DictationLanguage.PT, vocabulary = emptyList(), onFinished = {})
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
            val session = DictationSession(app, app.appScope, PolishStyle.FORMAL, DictationLanguage.PT, vocabulary = emptyList(), onFinished = {})
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
            TecladoTheme {
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
            TecladoTheme {
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
        compose.setContent { TecladoTheme { ModelsScreen(onBack = {}) } }
        shot("app_models")
    }

    @Test
    fun dictationSettingsDark() {
        RuntimeEnvironment.setQualifiers("+night")
        compose.setContent { TecladoTheme { DictationSettingsScreen(onBack = {}) } }
        shot("app_dictation_settings_dark")
    }

    private fun session(app: TecladoApp, mode: DictationMode) =
        DictationSession(app, app.appScope, PolishStyle.OFF, DictationLanguage.PT, mode = mode, vocabulary = emptyList(), onFinished = {})

    @Test
    fun dictationCode() {
        keyboard { c, app ->
            val s = session(app, DictationMode.CODE)
            s.showPreviewState(DictationUiState(phase = DictationPhase.LISTENING, transcript = "quatro oito dois nove", speaking = true, mode = DictationMode.CODE), levels())
            c.showPreview(s)
        }
        shot("dictation_code")
    }

    @Test
    fun dictationPasswordDone() {
        keyboard { c, app ->
            val s = session(app, DictationMode.SECRET)
            s.showPreviewState(DictationUiState(phase = DictationPhase.DONE, transcript = "a maiúsculo b c um dois arroba", polished = "Abc12@", mode = DictationMode.SECRET), FloatArray(48))
            c.showPreview(s)
        }
        shot("dictation_password")
    }

    @Test
    fun writingTools() {
        keyboard { c, _ -> c.showPanel(com.fernandogodinho.teclado.ime.Panel.TOOLS) }
        shot("writing_tools")
    }

    @Test
    fun oneHanded() {
        val app = RuntimeEnvironment.getApplication() as TecladoApp
        app.settings.setOneHanded(com.fernandogodinho.teclado.core.OneHandedMode.RIGHT)
        Thread.sleep(300)
        keyboard()
        shot("keyboard_one_handed")
        app.settings.setOneHanded(com.fernandogodinho.teclado.core.OneHandedMode.OFF)
        Thread.sleep(300)
    }
}
