package com.fernandogodinho.teclado.tv

import com.fernandogodinho.teclado.core.LocalNetwork
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket

/**
 * Tiny network helpers for talking to TVs on the local network only. Every connection checks
 * [LocalNetwork.requireLocal] first, so nothing here can reach the internet.
 */
object LanHttp {
    private const val MAX_BODY = 128 * 1024

    /** Plain HTTP request (TVs expose their control APIs without TLS on the home network). */
    fun request(host: InetAddress, port: Int, method: String, path: String, timeoutMs: Int = 4_000): String? {
        LocalNetwork.requireLocal(host)
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val req = "$method $path HTTP/1.1\r\nHost: ${host.hostAddress}:$port\r\nConnection: close\r\n" +
                    (if (method == "POST") "Content-Length: 0\r\n" else "") + "\r\n"
                s.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
                val raw = readLimited(s.getInputStream())
                val text = String(raw, Charsets.UTF_8)
                val headEnd = text.indexOf("\r\n\r\n")
                if (headEnd < 0) return@use null
                val head = text.substring(0, headEnd)
                val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (status !in 200..299) return@use null
                val body = text.substring(headEnd + 4)
                if (head.contains("transfer-encoding: chunked", ignoreCase = true)) dechunk(body) else body
            }
        }.getOrNull()
    }

    fun get(host: InetAddress, port: Int, path: String) = request(host, port, "GET", path)
    fun post(host: InetAddress, port: Int, path: String) = request(host, port, "POST", path)

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < MAX_BODY) {
            val n = try { input.read(buf) } catch (e: SocketTimeoutException) { break }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun dechunk(body: String): String {
        val sb = StringBuilder()
        var rest = body
        while (true) {
            val nl = rest.indexOf("\r\n")
            if (nl < 0) break
            val size = rest.substring(0, nl).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = nl + 2
            if (start + size > rest.length) {
                sb.append(rest.substring(start))
                break
            }
            sb.append(rest, start, start + size)
            rest = rest.substring(start + size).removePrefix("\r\n")
        }
        return sb.toString()
    }

    /** Value of an XML element (good enough for UPnP device descriptions). */
    fun xmlValue(xml: String, tag: String): String? =
        Regex("<$tag>([^<]{1,200})</$tag>").find(xml)?.groupValues?.get(1)?.trim()?.let(::unescapeXml)

    private fun unescapeXml(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}

/** One answer to an SSDP search. */
data class SsdpReply(val address: InetAddress, val headers: Map<String, String>) {
    val st get() = headers["st"].orEmpty()
    val server get() = headers["server"].orEmpty()
    val location get() = headers["location"].orEmpty()
}

/** SSDP (UPnP) discovery: how Samsung, LG and Roku TVs announce themselves on the Wi-Fi. */
object Ssdp {
    private val GROUP = InetAddress.getByName("239.255.255.250")
    private const val PORT = 1900

    fun search(targets: List<String>, timeoutMs: Int = 3_000): List<SsdpReply> {
        val replies = LinkedHashMap<String, SsdpReply>()
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.timeToLive = 2
            socket.soTimeout = 400
            repeat(2) {
                targets.forEach { st ->
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = msg.toByteArray(Charsets.US_ASCII)
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, GROUP, PORT)) }
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val from = packet.address ?: continue
                if (!LocalNetwork.isLocal(from)) continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val headers = text.lineSequence().drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                }.toMap()
                replies["${from.hostAddress}|${headers["st"]}"] = SsdpReply(from, headers)
            }
        }
        return replies.values.toList()
    }
}

/**
 * Minimal WebSocket client (RFC 6455) over a socket we already opened (plain or TLS), enough for
 * the JSON channels of Samsung and LG TVs. Text frames only; pings are answered.
 */
class WebSocketClient private constructor(private val socket: Socket) : AutoCloseable {
    private val input = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val writeLock = Any()
    private val random = SecureRandom()

    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    /** Read timeout in ms (0 = wait forever). A timeout surfaces as an exception from [receiveText]. */
    fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
        socket.keepAlive = true
    }

    /** Next text message; throws [EOFException] when the TV closes the connection. */
    fun receiveText(): String {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = readByte()
            val b1 = readByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
            else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or readByte().toLong() }
            }
            require(len in 0..MAX_MESSAGE) { "message too large" }
            val masked = b1 and 0x80 != 0
            val mask = if (masked) ByteArray(4) { readByte().toByte() } else null
            val payload = readFully(len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> throw EOFException("closed by the TV")
                0x9 -> sendFrame(0xA, payload)
                0xA -> Unit
                0x0, 0x1, 0x2 -> {
                    message.write(payload)
                    require(message.size() <= MAX_MESSAGE) { "message too large" }
                    if (fin) return message.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65536 -> {
                header.write(0x80 or 126)
                header.write(payload.size shr 8)
                header.write(payload.size and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) header.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also(random::nextBytes)
        header.write(mask)
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }
        synchronized(writeLock) {
            output.write(header.toByteArray() + masked)
            output.flush()
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException()
            read += r
        }
        return buf
    }

    override fun close() {
        runCatching { sendFrame(0x8, ByteArray(0)) }
        runCatching { socket.close() }
    }

    companion object {
        private const val MAX_MESSAGE = 1_000_000L

        /**
         * Opens a WebSocket on [socket] (already connected; TLS already negotiated if secure).
         * [hostHeader] is "ip:port". Throws when the TV refuses the upgrade.
         */
        fun open(socket: Socket, hostHeader: String, path: String): WebSocketClient {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            val req = "GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
            socket.getOutputStream().apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
            val head = StringBuilder()
            val input = socket.getInputStream()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw EOFException("no handshake")
                head.append(b.toChar())
                require(head.length < 8192) { "handshake too long" }
            }
            val status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
            if (status != 101) throw java.io.IOException("websocket refused: $status")
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            val accept = head.lineSequence().firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            if (accept != expected) throw java.io.IOException("bad websocket accept")
            return WebSocketClient(socket)
        }

        /** Connects (plain or TLS with the pinning trust manager) and opens the WebSocket. */
        fun connect(host: InetAddress, port: Int, path: String, tls: TvIdentity.PinningTrustManager?, timeoutMs: Int = 5_000): WebSocketClient {
            LocalNetwork.requireLocal(host)
            val socket: Socket = if (tls != null) {
                (TvIdentity.trustOnlyContext(tls).socketFactory.createSocket() as SSLSocket)
            } else {
                Socket()
            }
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = 0
                (socket as? SSLSocket)?.startHandshake()
                return open(socket, "${host.hostAddress}:$port", path)
            } catch (t: Throwable) {
                runCatching { socket.close() }
                throw t
            }
        }
    }
}
