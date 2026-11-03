package com.fernandogodinho.teclado

import android.app.ActivityManager
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
import com.fernandogodinho.teclado.engine.BenchmarkReport
import com.fernandogodinho.teclado.engine.SpeechBench
import com.fernandogodinho.teclado.engine.TextBench
import com.fernandogodinho.teclado.ime.TecladoIme
import com.fernandogodinho.teclado.ime.KeyboardController
import com.fernandogodinho.teclado.ime.ShiftState
import com.fernandogodinho.teclado.ime.Suggestion
import com.fernandogodinho.teclado.ime.ui.KeyboardRoot
import com.fernandogodinho.teclado.ui.SetupState
import com.fernandogodinho.teclado.ui.screens.BenchmarkScreen
import com.fernandogodinho.teclado.ui.screens.DictationSettingsScreen
import com.fernandogodinho.teclado.ui.screens.KeyboardSettingsScreen
import com.fernandogodinho.teclado.ui.screens.HomeScreen
import com.fernandogodinho.teclado.ui.screens.ModelsScreen
import com.fernandogodinho.teclado.ui.screens.OnboardingScreen
import com.fernandogodinho.teclado.ui.theme.TecladoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/** Renders the main screens to build/screenshots for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun typicalPhone() {
        // An 8 GB phone, so recommendations and warnings look like they do on a real device.
        val am = RuntimeEnvironment.getApplication().getSystemService(ActivityManager::class.java)
        Shadows.shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 8L * 1024 * 1024 * 1024 })
    }

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
                    setup = SetupState(enabled = true, selected = true, micGranted = true, speechModelReady = true, textModelReady = true),
                    navigate = {}, onEnable = {}, onSelect = {}, onMic = {},
                )
            }
        }
        shot("app_home")
    }

    @Test
    fun homeDark() {
        RuntimeEnvironment.setQualifiers("+night")
        compose.setContent {
            TecladoTheme {
                HomeScreen(
                    setup = SetupState(enabled = true, selected = true, micGranted = true, speechModelReady = true, textModelReady = true),
                    navigate = {}, onEnable = {}, onSelect = {}, onMic = {},
                )
            }
        }
        shot("app_home_dark")
    }

    @Test
    fun keyboardSettingsDark() {
        RuntimeEnvironment.setQualifiers("+night")
        compose.setContent { TecladoTheme { KeyboardSettingsScreen(onBack = {}) } }
        shot("app_keyboard_settings_dark")
    }

    // Illustrative numbers for the screenshot only (not a real measurement).
    private fun sampleReport() = BenchmarkReport(
        createdAt = 1_793_000_000_000L,
        device = "Celular de exemplo",
        ramGb = 8.0,
        threads = 4,
        appVersion = "1.1.0",
        speech = listOf(
            SpeechBench("whisper-base-q8", 310, 1_150, 15_400, "As crônicas da vila de Itaguaí dizem que em tempos remotos vivera ali um certo médico, o doutor Simão Bacamarte, filho da nobreza da terra e o maior dos médicos do Brasil, de Portugal e das Espanhas.", 0.89),
            SpeechBench("whisper-large-v3-turbo-q5", 1_900, 9_800, 15_400, "As crônicas da vila de Itaguaí dizem que, em tempos remotos, vivera ali um certo médico, o doutor Simão Bacamarte, filho da nobreza da terra e o maior dos médicos do Brasil, de Portugal e das Espanhas.", 1.0),
        ),
        text = listOf(
            TextBench("gemma-4-e2b-q4", 2_400, 5_100, 55, 82, 64, 900, 6_300, "A reunião de amanhã será às 16h. Peço que cada um traga os números do mês, pois o diretor vai querer ver tudo. Quem não puder comparecer, por favor, avise-me até hoje à noite."),
        ),
    )

    // Taller screen so the website can show the results below the intro.
    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h1100dp-xxhdpi")
    fun benchmark() {
        val app = RuntimeEnvironment.getApplication() as TecladoApp
        app.benchmark.showPreview(sampleReport())
        compose.setContent { TecladoTheme { BenchmarkScreen(onBack = {}, onModels = {}) } }
        shot("app_benchmark")
    }

    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h1100dp-xxhdpi")
    fun benchmarkDark() {
        RuntimeEnvironment.setQualifiers("+night")
        val app = RuntimeEnvironment.getApplication() as TecladoApp
        app.benchmark.showPreview(sampleReport())
        compose.setContent { TecladoTheme { BenchmarkScreen(onBack = {}, onModels = {}) } }
        shot("app_benchmark_dark")
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
    fun keyboardTrackpad() {
        keyboard { c, _ -> c.onTrackpad(true) }
        shot("keyboard_trackpad")
    }

    @Test
    fun keyboardSmartChips() {
        keyboard { c, _ ->
            c.showSmartPreview(
                chips = listOf(
                    com.fernandogodinho.teclado.ime.SmartChip("1.250,50 × 3 = 3.751,5", com.fernandogodinho.teclado.ime.SmartKind.CALCULATOR, "calc") {},
                    com.fernandogodinho.teclado.ime.SmartChip("10 km = 6,2137 mi", com.fernandogodinho.teclado.ime.SmartKind.CONVERSION, "conv") {},
                ),
            )
        }
        shot("keyboard_smart_chips")
    }

    @Test
    fun toolPreview() {
        keyboard { c, _ ->
            c.showSmartPreview(
                result = com.fernandogodinho.teclado.ime.ToolResult(
                    tool = com.fernandogodinho.teclado.engine.WritingTool.FORMAL,
                    original = "oi pessoal amanhã não vai ter aula por causa da chuva",
                    output = "Prezados, informamos que amanhã não haverá aula devido à chuva.",
                    replaceSelection = false, beforeLen = 0, afterLen = 0,
                ),
            )
        }
        shot("keyboard_tool_preview")
    }

    private fun themed(theme: com.fernandogodinho.teclado.ime.ui.KeyboardThemeId, name: String) {
        val app = RuntimeEnvironment.getApplication() as TecladoApp
        app.settings.setKeyboardTheme(theme)
        Thread.sleep(300)
        keyboard { c, _ -> c.showPreview(null, listOf(Suggestion("voce", verbatim = true), Suggestion("você", autoCorrect = true), Suggestion("vocês"))) }
        shot(name)
        app.settings.setKeyboardTheme(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.CLASSIC)
        Thread.sleep(300)
    }

    @Test fun themeGraphite() = themed(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.GRAPHITE, "theme_graphite")
    @Test fun themeMidnight() = themed(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.MIDNIGHT, "theme_midnight")
    @Test fun themeSage() = themed(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.SAGE, "theme_sage")
    @Test fun themeSand() = themed(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.SAND, "theme_sand")
    @Test fun themeLavender() = themed(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.LAVENDER, "theme_lavender")
    @Test fun themeHighContrast() = themed(com.fernandogodinho.teclado.ime.ui.KeyboardThemeId.HIGH_CONTRAST, "theme_high_contrast")

    @Test
    fun keyboardSettingsLight() {
        compose.setContent { TecladoTheme { KeyboardSettingsScreen(onBack = {}) } }
        shot("app_keyboard_settings")
    }

    @Test
    fun tvScreen() {
        compose.setContent { TecladoTheme { com.fernandogodinho.teclado.ui.screens.TvScreen(onBack = {}) } }
        shot("app_tv")
    }

    @Test
    fun supportScreen() {
        compose.setContent { TecladoTheme { com.fernandogodinho.teclado.ui.screens.SupportScreen(onBack = {}) } }
        shot("app_support")
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
