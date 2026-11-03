package com.fernandogodinho.teclado.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A TV the user paired with this phone. */
data class PairedTv(
    /** The name the TV announces on the network (also used as its id). */
    val name: String,
    /** Last known address (TVs may get a new one; discovery updates it). */
    val host: String,
    /** SHA-256 of the TV certificate (TLS protocols); null until the first connection. */
    val pin: String?,
    val pairedAt: Long,
    val platform: TvPlatform = TvPlatform.ANDROID_TV,
    /** Samsung token or LG client key returned by the TV after the user allowed this phone. */
    val token: String? = null,
)

/** Paired TVs, kept in the app's private storage. Contains no text the user typed. */
class TvStore(context: Context) {
    private val file = File(context.filesDir, "tvs.json")

    @Synchronized
    fun load(): List<PairedTv> = runCatching {
        if (!file.isFile) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PairedTv(
                name = o.getString("name"),
                host = o.getString("host"),
                pin = o.optString("pin").ifEmpty { null },
                pairedAt = o.optLong("pairedAt"),
                platform = TvPlatform.entries.firstOrNull { it.name == o.optString("platform") } ?: TvPlatform.ANDROID_TV,
                token = o.optString("token").ifEmpty { null },
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tvs: List<PairedTv>) {
        val a = JSONArray()
        tvs.forEach {
            a.put(
                JSONObject().put("name", it.name).put("host", it.host).put("pin", it.pin ?: "").put("pairedAt", it.pairedAt)
                    .put("platform", it.platform.name).put("token", it.token ?: ""),
            )
        }
        val tmp = File(file.parentFile, "tvs.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }
}
