package com.fernandogodinho.teclado.tv

import android.os.Build
import android.util.Log
import com.fernandogodinho.teclado.core.LocalNetwork
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class WrongCodeException : Exception("wrong pairing code")
class PairingRejectedException : Exception("pairing rejected by the TV")

private fun openTls(host: InetAddress, port: Int, trust: TvIdentity.PinningTrustManager, readTimeoutMs: Int): SSLSocket {
    LocalNetwork.requireLocal(host)
    val socket = TvIdentity.sslContext(trust).socketFactory.createSocket() as SSLSocket
    try {
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.soTimeout = readTimeoutMs
        socket.startHandshake()
    } catch (t: Throwable) {
        runCatching { socket.close() }
        throw t
    }
    return socket
}

/**
 * Pairing with an Android TV / Google TV: the TV shows a 6-character code and the user types it
 * on the phone. Returns the TV certificate fingerprint, pinned for every later connection.
 */
class AndroidTvPairing(private val host: InetAddress, private val clientName: String) : Closeable {
    private val trust = TvIdentity.PinningTrustManager(expectedSha256 = null)
    private var socket: SSLSocket? = null

    /** Connects and asks the TV to display the code. Blocking; call off the main thread. */
    fun start() {
        TvIdentity.ensure()
        val s = openTls(host, AtvProtocol.PAIRING_PORT, trust, readTimeoutMs = 15_000)
        socket = s
        exchange(AtvProtocol.pairingRequest(clientName), AtvProtocol.PairingReply.REQUEST_ACK)
        exchange(AtvProtocol.pairingOptions(), AtvProtocol.PairingReply.OPTIONS)
        exchange(AtvProtocol.pairingConfiguration(), AtvProtocol.PairingReply.CONFIGURATION_ACK)
    }

    /** Sends the code typed by the user. Blocking. */
    fun finish(code: String): String {
        val server = trust.seen ?: throw IllegalStateException("not connected")
        val client = TvIdentity.publicKey
        val serverKey = server.publicKey as? RSAPublicKey ?: throw PairingRejectedException()
        val secret = AtvProtocol.pairingSecret(client.modulus, client.publicExponent, serverKey.modulus, serverKey.publicExponent, code)
            ?: throw WrongCodeException()
        exchange(AtvProtocol.pairingSecret(secret), AtvProtocol.PairingReply.SECRET_ACK)
        return TvIdentity.fingerprint(server)
    }

    private fun exchange(message: ByteArray, expected: AtvProtocol.PairingReply) {
        val s = socket ?: throw IllegalStateException("not connected")
        Proto.writeFrame(s.outputStream, message)
        val reply = AtvProtocol.parsePairing(Proto.readFrame(s.inputStream))
        if (reply == AtvProtocol.PairingReply.ERROR && expected == AtvProtocol.PairingReply.SECRET_ACK) throw WrongCodeException()
        if (reply != expected) throw PairingRejectedException()
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

/**
 * A live remote-control session with a paired TV: answers its pings, learns when a text field
 * gets focus and replaces that field's text with what the user types on the phone.
 */
class AndroidTvSession(
    private val host: InetAddress,
    private val pinnedSha256: String?,
    private val listener: Listener,
) : Closeable {

    interface Listener {
        fun onReady(session: AndroidTvSession)
        fun onTextFieldRequested(session: AndroidTvSession, label: String, value: String)
        fun onTextFieldUpdate(session: AndroidTvSession, fieldCounter: Int)
        fun onClosed(session: AndroidTvSession, error: Throwable?)
        /** First certificate seen when nothing was pinned yet (right after pairing). */
        fun onCertificate(session: AndroidTvSession, sha256: String) = Unit
    }

    private val trust = TvIdentity.PinningTrustManager(pinnedSha256)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var closed = false
    private var out: OutputStream? = null
    private val writeLock = Any()

    @Volatile var imeCounter = 0
        private set
    @Volatile var fieldCounter = 0
        private set
    @Volatile var ready = false
        private set

    /** Connects (blocking) and starts listening on a background thread. */
    fun open() {
        val s = openTls(host, AtvProtocol.REMOTE_PORT, trust, readTimeoutMs = 20_000)
        socket = s
        out = s.outputStream
        if (pinnedSha256 == null) trust.seen?.let { listener.onCertificate(this, TvIdentity.fingerprint(it)) }
        Thread({ readLoop(s) }, "teclado-tv").apply { isDaemon = true }.start()
    }

    private fun readLoop(s: SSLSocket) {
        var error: Throwable? = null
        try {
            while (!closed) {
                when (val msg = AtvProtocol.parseRemote(Proto.readFrame(s.inputStream))) {
                    is AtvProtocol.Incoming.Configure -> {
                        // Only ask for what both sides support.
                        val supported = if (msg.features != 0) msg.features else AtvProtocol.FEATURES
                        val features = AtvProtocol.FEATURES and supported
                        send(AtvProtocol.configure(features, model = "Teclado do Fernando Godinho", vendor = Build.MANUFACTURER.orEmpty()))
                    }
                    is AtvProtocol.Incoming.SetActive -> {
                        send(AtvProtocol.setActive(AtvProtocol.FEATURES))
                        if (!ready) {
                            ready = true
                            listener.onReady(this)
                        }
                    }
                    is AtvProtocol.Incoming.Ping -> send(AtvProtocol.pingResponse(msg.value))
                    is AtvProtocol.Incoming.ImeCounters -> {
                        imeCounter = msg.imeCounter
                        if (msg.fieldCounter != fieldCounter) {
                            fieldCounter = msg.fieldCounter
                            listener.onTextFieldUpdate(this, fieldCounter)
                        }
                    }
                    is AtvProtocol.Incoming.TextField -> listener.onTextFieldRequested(this, msg.label, msg.value)
                    is AtvProtocol.Incoming.Power -> if (!ready) {
                        ready = true
                        listener.onReady(this)
                    }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            if (!closed) error = t
        } finally {
            close()
            listener.onClosed(this, error)
        }
    }

    private fun send(message: ByteArray) {
        synchronized(writeLock) {
            val o = out ?: throw IllegalStateException("not connected")
            Proto.writeFrame(o, message)
        }
    }

    /** Replaces the text of the focused field on the TV. Never logs the text. */
    fun setText(text: String) {
        runCatching { send(AtvProtocol.setText(text, imeCounter, fieldCounter)) }
            .onFailure { Log.w(TAG, "could not send text to the TV") }
    }

    fun key(code: Int) {
        runCatching { send(AtvProtocol.key(code)) }.onFailure { Log.w(TAG, "could not send key to the TV") }
    }

    override fun close() {
        closed = true
        ready = false
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    companion object {
        private const val TAG = "AndroidTvSession"
    }
}
