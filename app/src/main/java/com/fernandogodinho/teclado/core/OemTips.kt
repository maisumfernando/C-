package com.fernandogodinho.teclado.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Manufacturer-specific guidance (Huawei/EMUI, Honor, Xiaomi, Oppo, Vivo...). */
object OemTips {
    private val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()

    val isHuaweiFamily: Boolean get() = manufacturer.contains("huawei") || manufacturer.contains("honor")

    /** Brands known for pausing background work aggressively. */
    val aggressiveBackgroundLimits: Boolean
        get() = listOf("huawei", "honor", "xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "oneplus", "meizu", "tecno", "infinix")
            .any { manufacturer.contains(it) }

    val brandName: String get() = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercaseChar() }.ifBlank { "este celular" }

    fun openBatterySettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
        for (intent in intents) {
            val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
    }

    fun openKeyboardSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
