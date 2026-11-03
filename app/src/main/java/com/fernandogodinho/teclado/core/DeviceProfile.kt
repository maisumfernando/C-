package com.fernandogodinho.teclado.core

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import java.io.File

/** Hardware facts used to tune inference and recommend models. */
object DeviceProfile {

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    fun freeStorageBytes(dir: File): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)

    /**
     * Number of threads for inference: the "big" cores only. Little cores slow down
     * ggml's synchronous thread pool because every thread waits for the slowest one.
     */
    val inferenceThreads: Int by lazy {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        val cores = Runtime.getRuntime().availableProcessors()
        val big = if (freqs.size == cores && freqs.isNotEmpty()) {
            val max = freqs.max()
            freqs.count { it >= max * 0.65 }
        } else {
            cores / 2
        }
        big.coerceIn(2, 6)
    }
}
