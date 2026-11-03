package com.fernandogodinho.teclado.core

import android.os.SystemClock

/**
 * Hands text from the composition editor back to the field it was opened from. Kept only in
 * memory, for two minutes at most; never written to disk.
 */
class ComposerHandoff {
    data class Pending(val text: String, val original: String, val replaceSelection: Boolean)

    private var packageName = ""
    private var fieldId = 0
    @Volatile var draft: String = ""
        private set
    private var original = ""
    private var ready: Pending? = null
    private var readyAt = 0L

    @Synchronized
    fun begin(packageName: String, fieldId: Int, selected: String) {
        this.packageName = packageName
        this.fieldId = fieldId
        original = selected
        draft = selected
        ready = null
    }

    /** Called by the editor when the user taps "Inserir". */
    @Synchronized
    fun finish(text: String) {
        ready = Pending(text, original, replaceSelection = original.isNotEmpty())
        readyAt = SystemClock.elapsedRealtime()
        draft = ""
    }

    @Synchronized
    fun discard() {
        ready = null
        draft = ""
        original = ""
    }

    /** The composed text, if [packageName]/[fieldId] is where the editor was opened from. */
    @Synchronized
    fun takeFor(packageName: String, fieldId: Int): Pending? {
        val p = ready ?: return null
        if (SystemClock.elapsedRealtime() - readyAt > 120_000) {
            ready = null
            return null
        }
        if (packageName != this.packageName || fieldId != this.fieldId) return null
        ready = null
        original = ""
        return p
    }
}
