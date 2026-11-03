package com.fernandogodinho.teclado.tv

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Base64

/** What to delete from the end and what to type so the TV goes from [old] to [new]. */
object TextDiff {
    data class Edit(val delete: Int, val insert: String)

    fun between(old: String, new: String): Edit {
        var p = 0
        val max = minOf(old.length, new.length)
        while (p < max && old[p] == new[p]) p++
        // Never split a surrogate pair (emoji).
        if (p > 0 && p < old.length && Character.isLowSurrogate(old[p])) p--
        return Edit(old.length - p, new.substring(p))
    }
}

private const val CLIENT_NAME = "Teclado do Fernando Godinho"

/** Next message as JSON (null if it is not JSON). Connection errors propagate to the caller. */
private fun WebSocketClient.nextJson(): JSONObject? {
    val text = receiveText()
    return runCatching { JSONObject(text) }.getOrNull()
}
private const val TAG = "TvBrands"

/**
 * Samsung Smart TVs (Tizen, 2016 and later): JSON over a WebSocket on port 8002 (TLS) or 8001.
 * The first connection makes the TV ask "Permitir?"; the TV then gives a token for next time.
 * When a text field opens on the TV it sends "ms.remote.imeStart".
 */
class SamsungTvSession(
    private val host: InetAddress,
    private val token: String?,
    private val pin: String?,
    private val listener: TvSessionListener,
) : TvSession {
    @Volatile private var ws: WebSocketClient? = null
    @Volatile private var closed = false
    @Volatile override var ready = false
        private set

    override fun open() {
        val name = URLEncoder.encode(Base64.getEncoder().encodeToString(CLIENT_NAME.toByteArray()), "UTF-8")
        val trust = TvIdentity.PinningTrustManager(pin)
        val path = "/api/v2/channels/samsung.remote.control?name=$name" + (token?.let { "&token=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        val socket = try {
            WebSocketClient.connect(host, 8002, path, trust)
        } catch (e: IOException) {
            // 2016–2017 models only have the plain port (and no token).
            WebSocketClient.connect(host, 8001, "/api/v2/channels/samsung.remote.control?name=$name", null)
        }
        ws = socket
        if (pin == null) trust.seen?.let { listener.onCredentials(this, null, TvIdentity.fingerprint(it)) }
        socket.setReadTimeout(45_000)
        // Wait for the user to allow this phone on the TV (or for the TV to reject it).
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            val msg = socket.nextJson() ?: continue
            when (msg.optString("event")) {
                "ms.channel.connect" -> {
                    msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }?.let { listener.onCredentials(this, it, null) }
                    socket.setReadTimeout(0)
                    ready = true
                    listener.onReady(this)
                    Thread({ readLoop(socket) }, "teclado-tv-samsung").apply { isDaemon = true }.start()
                    return
                }
                "ms.channel.unauthorized", "ms.channel.timeOut" -> {
                    close()
                    throw TvRejectedException("A TV não permitiu a conexão")
                }
            }
        }
        close()
        throw TvRejectedException("A TV não respondeu a tempo")
    }

    private fun readLoop(socket: WebSocketClient) {
        var error: Throwable? = null
        try {
            while (!closed) {
                val msg = socket.nextJson() ?: continue
                if (msg.optString("event") == "ms.remote.imeStart") listener.onTextFieldRequested(this, "", "")
            }
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            close()
            listener.onClosed(this, error)
        }
    }

    private fun control(params: JSONObject) {
        runCatching { ws?.sendText(JSONObject().put("method", "ms.remote.control").put("params", params).toString()) }
            .onFailure { Log.w(TAG, "Samsung: send failed") }
    }

    override fun setText(text: String) {
        control(
            JSONObject().put("Cmd", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
                .put("DataOfCmd", "base64").put("TypeOfRemote", "SendInputString"),
        )
    }

    override fun key(key: TvKey) = when (key) {
        TvKey.ENTER -> control(JSONObject().put("TypeOfRemote", "SendInputEnd"))
        TvKey.BACK -> control(JSONObject().put("Cmd", "Click").put("DataOfCmd", "KEY_RETURN").put("Option", "false").put("TypeOfRemote", "SendRemoteKey"))
    }

    override fun close() {
        closed = true
        ready = false
        ws?.close()
        ws = null
    }
}

/**
 * LG Smart TVs (webOS): JSON over a WebSocket on port 3001 (TLS) or 3000. The TV asks the user to
 * accept the connection once and returns a "client-key". Text uses the TV's own IME service, and
 * a subscription tells us when a text field gets focus.
 */
class LgTvSession(
    private val host: InetAddress,
    private val clientKey: String?,
    private val pin: String?,
    private val listener: TvSessionListener,
) : TvSession {
    @Volatile private var ws: WebSocketClient? = null
    @Volatile private var closed = false
    @Volatile override var ready = false
        private set
    private var nextId = 1
    private var lastText = ""

    override fun open() {
        val trust = TvIdentity.PinningTrustManager(pin)
        val socket = try {
            WebSocketClient.connect(host, 3001, "/", trust)
        } catch (e: IOException) {
            WebSocketClient.connect(host, 3000, "/", null)
        }
        ws = socket
        if (pin == null) trust.seen?.let { listener.onCredentials(this, null, TvIdentity.fingerprint(it)) }
        socket.setReadTimeout(60_000)
        socket.sendText(registration(clientKey).toString())
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val msg = socket.nextJson() ?: continue
            when (msg.optString("type")) {
                "registered" -> {
                    msg.optJSONObject("payload")?.optString("client-key")?.takeIf { it.isNotBlank() }
                        ?.let { listener.onCredentials(this, it, null) }
                    // Ask to be told whenever a text field gets focus on the TV.
                    socket.sendText(request("subscribe", "ssap://com.webos.service.ime/registerRemoteKeyboard", JSONObject(), id = "keyboard").toString())
                    socket.setReadTimeout(0)
                    ready = true
                    listener.onReady(this)
                    Thread({ readLoop(socket) }, "teclado-tv-lg").apply { isDaemon = true }.start()
                    return
                }
                "error" -> {
                    close()
                    throw TvRejectedException("A TV recusou a conexão")
                }
            }
        }
        close()
        throw TvRejectedException("A TV não respondeu a tempo")
    }

    private fun readLoop(socket: WebSocketClient) {
        var error: Throwable? = null
        try {
            while (!closed) {
                val msg = socket.nextJson() ?: continue
                if (msg.optString("id") != "keyboard") continue
                val widget = msg.optJSONObject("payload")?.optJSONObject("currentWidget") ?: continue
                if (widget.optBoolean("focus")) {
                    lastText = ""
                    listener.onTextFieldRequested(this, "", "")
                }
            }
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            close()
            listener.onClosed(this, error)
        }
    }

    private fun request(type: String, uri: String, payload: JSONObject, id: String = "req_${nextId++}") =
        JSONObject().put("id", id).put("type", type).put("uri", uri).put("payload", payload)

    private fun send(uri: String, payload: JSONObject) {
        runCatching { ws?.sendText(request("request", uri, payload).toString()) }.onFailure { Log.w(TAG, "LG: send failed") }
    }

    @Synchronized
    override fun setText(text: String) {
        val edit = TextDiff.between(lastText, text)
        if (edit.delete > 0) send("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", edit.delete))
        if (edit.insert.isNotEmpty()) send("ssap://com.webos.service.ime/insertText", JSONObject().put("text", edit.insert).put("replace", 0))
        lastText = text
    }

    override fun key(key: TvKey) {
        when (key) {
            TvKey.ENTER -> send("ssap://com.webos.service.ime/sendEnterKey", JSONObject())
            // "Back" needs LG's pointer-input socket; the TV remote is easier for that.
            TvKey.BACK -> Unit
        }
    }

    override fun close() {
        closed = true
        ready = false
        ws?.close()
        ws = null
    }

    companion object {
        /** Registration request (unsigned manifest, the same permissions the open-source clients ask for). */
        fun registration(clientKey: String?): JSONObject {
            val permissions = listOf(
                "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_INPUT_JOYSTICK", "READ_INPUT_DEVICE_LIST",
                "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_CURRENT_CHANNEL", "READ_APP_STATUS",
            )
            val payload = JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put(
                    "manifest",
                    JSONObject().put("manifestVersion", 1).put("appVersion", "1.1")
                        .put("permissions", org.json.JSONArray(permissions)),
                )
            if (!clientKey.isNullOrBlank()) payload.put("client-key", clientKey)
            return JSONObject().put("type", "register").put("id", "register_0").put("payload", payload)
        }
    }
}

/**
 * Roku TVs (TCL, Semp, AOC, Philco Roku TV…): the External Control Protocol on port 8060.
 * No pairing; the TV only needs "Controle por aplicativos móveis" enabled. Text is typed key by
 * key. Roku does not say when a text field opens, so typing starts from the phone.
 */
class RokuTvSession(
    private val host: InetAddress,
    private val listener: TvSessionListener,
) : TvSession {
    @Volatile override var ready = false
        private set
    private var lastText = ""

    override fun open() {
        LanHttp.get(host, 8060, "/query/device-info") ?: throw IOException("Roku did not answer")
        ready = true
        listener.onReady(this)
    }

    private fun press(key: String): Boolean = LanHttp.post(host, 8060, "/keypress/$key") != null

    @Synchronized
    override fun setText(text: String) {
        val edit = TextDiff.between(lastText, text)
        repeat(edit.delete) { press("Backspace") }
        var i = 0
        while (i < edit.insert.length) {
            val cp = edit.insert.codePointAt(i)
            press("Lit_" + URLEncoder.encode(String(Character.toChars(cp)), "UTF-8").replace("+", "%20"))
            i += Character.charCount(cp)
        }
        lastText = text
    }

    override fun key(key: TvKey) {
        press(if (key == TvKey.ENTER) "Enter" else "Back")
        if (key == TvKey.ENTER) synchronized(this) { lastText = "" }
    }

    override fun close() {
        if (ready) {
            ready = false
            listener.onClosed(this, null)
        }
    }
}

/** Reads the TV's own name for the list (falls back to the brand). */
object TvNames {
    fun samsung(host: InetAddress): String? = LanHttp.get(host, 8001, "/api/v2/")?.let { body ->
        runCatching { JSONObject(body).optJSONObject("device")?.optString("name")?.takeIf { it.isNotBlank() } }.getOrNull()
    }?.replace("[TV]", "")?.trim()

    fun roku(host: InetAddress): String? = LanHttp.get(host, 8060, "/query/device-info")?.let { xml ->
        LanHttp.xmlValue(xml, "user-device-name") ?: LanHttp.xmlValue(xml, "friendly-device-name") ?: LanHttp.xmlValue(xml, "model-name")
    }

    /** UPnP description (LG and others): <friendlyName>. Only local http URLs are fetched. */
    fun upnp(location: String): String? {
        val m = Regex("^http://([0-9a-fA-F.:\\[\\]]+):(\\d+)(/.*)$").find(location) ?: return null
        val host = runCatching { InetAddress.getByName(m.groupValues[1].trim('[', ']')) }.getOrNull() ?: return null
        val xml = LanHttp.get(host, m.groupValues[2].toInt(), m.groupValues[3]) ?: return null
        return LanHttp.xmlValue(xml, "friendlyName")
    }
}
