package app.escriba.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.mutableStateListOf

data class ClipItem(val text: String, val time: Long, val pinned: Boolean = false)

/**
 * Recent clipboard texts captured while the keyboard is active. Kept in memory only and
 * never recorded for content the source app marked as sensitive (passwords, OTPs).
 */
class ClipboardHistory(context: Context) {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    val items = mutableStateListOf<ClipItem>()
    var enabled = true

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    fun start() {
        runCatching { manager?.addPrimaryClipChangedListener(listener) }
        capture()
    }

    fun stop() {
        runCatching { manager?.removePrimaryClipChangedListener(listener) }
    }

    private fun capture() {
        if (!enabled) return
        val clip = runCatching { manager?.primaryClip }.getOrNull() ?: return
        if (isSensitive(clip.description)) return
        val text = clip.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > 5000) return
        val existing = items.indexOfFirst { it.text == text }
        val pinned = existing >= 0 && items[existing].pinned
        if (existing >= 0) items.removeAt(existing)
        items.add(0, ClipItem(text, System.currentTimeMillis(), pinned))
        while (items.size > MAX_ITEMS) {
            val idx = items.indexOfLast { !it.pinned }
            if (idx < 0) break
            items.removeAt(idx)
        }
    }

    fun togglePin(item: ClipItem) {
        val i = items.indexOf(item)
        if (i >= 0) items[i] = item.copy(pinned = !item.pinned)
    }

    fun remove(item: ClipItem) {
        items.remove(item)
    }

    fun clear() {
        items.removeAll { !it.pinned }
    }

    private fun isSensitive(description: ClipDescription?): Boolean {
        val extras: PersistableBundle = description?.extras ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
        } else {
            extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
        }
    }

    companion object {
        private const val MAX_ITEMS = 25
    }
}
