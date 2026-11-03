package com.fernandogodinho.teclado.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only way this app talks to the network.
 *
 * - HTTPS only (cleartext is also disabled by the network security config).
 * - Every hop, including redirects, must stay on an allowlisted host: Hugging Face for model
 *   files and GitHub for the update catalog. Nothing else can ever be contacted.
 * - No cookies, no credentials, a generic User-Agent and nothing about the user in requests.
 * - Responses that are read into memory are capped in size.
 *
 * The keyboard (input method) itself never calls this class: typed or dictated text never
 * reaches any network code path.
 */
object SecureHttp {

    /** Hosts allowed to serve model files (Hugging Face and its CDN). */
    private val modelHosts = listOf("huggingface.co", "hf.co")

    /** The project's own website (GitHub Pages) serves the update catalog and version info. */
    private val catalogHosts = listOf(ProjectInfo.PAGES_HOST)

    fun isAllowed(url: URL, allowedDomains: List<String>): Boolean {
        if (url.protocol != "https") return false
        if (url.userInfo != null) return false
        if (url.port != -1 && url.port != 443) return false
        val host = url.host.lowercase().trimEnd('.')
        return allowedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    fun isAllowedModelUrl(url: String): Boolean = runCatching { isAllowed(URL(url), modelHosts) }.getOrDefault(false)

    /** Opens a GET request for a model file, following redirects only within the allowlist. */
    fun openModel(url: String, rangeStart: Long): HttpURLConnection =
        open(url, modelHosts, rangeStart, accept = "application/octet-stream")

    /** Downloads a small text document (e.g. JSON) from the catalog hosts. */
    fun fetchText(url: String, maxBytes: Int): String {
        val c = open(url, catalogHosts, 0, accept = "application/json")
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            val declared = c.contentLengthLong
            if (declared > maxBytes) throw IOException("Resposta grande demais")
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Resposta grande demais")
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, allowed: List<String>, rangeStart: Long, accept: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            if (!isAllowed(current, allowed)) throw SecurityException("Destino não permitido: ${current.host}")
            val c = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 20_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Encoding", "identity")
                if (rangeStart > 0) setRequestProperty("Range", "bytes=$rangeStart-")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirecionamento inválido")
                current = URL(current, location)
            } else {
                return c
            }
        }
        throw IOException("Muitos redirecionamentos")
    }

    private const val MAX_REDIRECTS = 6
}
