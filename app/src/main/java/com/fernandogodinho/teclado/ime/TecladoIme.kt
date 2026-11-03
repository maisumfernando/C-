package com.fernandogodinho.teclado.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fernandogodinho.teclado.tecladoApp
import com.fernandogodinho.teclado.ime.ui.KeyboardRoot

/**
 * The keyboard. Hosts a Compose UI inside the input-method window and delegates all
 * editing logic to [KeyboardController].
 */
class TecladoIme : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    lateinit var controller: KeyboardController
        private set

    /** Height in px of the visible keyboard (the input view also has a transparent area above it for popups). */
    var keyboardHeightPx by mutableIntStateOf(0)

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        controller = KeyboardController(this, tecladoApp, lifecycleScope)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        controller.clipboard.enabled = tecladoApp.settings.current.clipboardHistory
        controller.clipboard.start()
    }

    override fun onCreateInputView(): View {
        val decor = window.window?.decorView
        decor?.setViewTreeLifecycleOwner(this)
        decor?.setViewTreeViewModelStoreOwner(this)
        decor?.setViewTreeSavedStateRegistryOwner(this)
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@TecladoIme)
            setViewTreeViewModelStoreOwner(this@TecladoIme)
            setViewTreeSavedStateRegistryOwner(this@TecladoIme)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { KeyboardRoot(this@TecladoIme, controller) }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        controller.clipboard.enabled = tecladoApp.settings.current.clipboardHistory
        controller.onStartInput(info, restarting)
        // Password fields: keep the keyboard out of screenshots, screen recordings and the
        // recent-apps preview so taps on keys can't be captured.
        window.window?.let { w ->
            if (controller.secureField) {
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        // Get the models ready early if the user dictates often.
        if (tecladoApp.engines.status.value.speechReady) tecladoApp.engines.touch()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        controller.onFinishInput()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        controller.onWindowHidden()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.onUpdateSelection(newSelStart, newSelEnd)
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = window.window?.decorView ?: return
        val input = inputViewOrNull() ?: return
        if (keyboardHeightPx <= 0 || input.height <= 0) return
        // Only the keyboard itself occupies space: the transparent strip above it (used for
        // key previews and accent popups) must neither push the app up nor eat its touches.
        val location = IntArray(2)
        input.getLocationInWindow(location)
        val top = location[1] + input.height - keyboardHeightPx
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, view.width, view.height)
    }

    private var inputViewRef: View? = null

    private fun inputViewOrNull(): View? = inputViewRef

    override fun setInputView(view: View?) {
        super.setInputView(view)
        inputViewRef = view
    }

    override fun onDestroy() {
        controller.cancelDictation()
        controller.clipboard.stop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }
}
