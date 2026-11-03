package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.tv.LgTvSession
import com.fernandogodinho.teclado.tv.TextDiff
import com.fernandogodinho.teclado.tv.WebSocketClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlin.concurrent.thread

class TvBrandsTest {

    @Test
    fun textDiffSendsOnlyWhatChanged() {
        assertEquals(TextDiff.Edit(0, "abc"), TextDiff.between("", "abc"))
        assertEquals(TextDiff.Edit(0, "s"), TextDiff.between("novela", "novelas"))
        assertEquals(TextDiff.Edit(2, ""), TextDiff.between("novelas", "novel"))
        assertEquals(TextDiff.Edit(2, "ão"), TextDiff.between("caçar", "cação"))
        // Never splits an emoji in half.
        val edit = TextDiff.between("oi 😀", "oi 😃")
        assertEquals(2, edit.delete)
        assertEquals("😃", edit.insert)
    }

    @Test
    fun lgRegistrationAsksForTextPermission() {
        val first = LgTvSession.registration(null)
        assertEquals("register", first.getString("type"))
        val payload = first.getJSONObject("payload")
        assertEquals("PROMPT", payload.getString("pairingType"))
        assertFalse(payload.has("client-key"))
        assertTrue(payload.getJSONObject("manifest").getJSONArray("permissions").toString().contains("CONTROL_INPUT_TEXT"))
        assertEquals("abc", LgTvSession.registration("abc").getJSONObject("payload").getString("client-key"))
    }

    private fun readHeaders(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().toChar())
        return sb.toString()
    }

    @Test
    fun webSocketHandshakeAndFrames() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var clientSaid = ""
            var gotPong = false
            val tv = thread {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    val out = s.getOutputStream()
                    val head = readHeaders(input)
                    val key = head.lineSequence().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
                    )
                    out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
                    // Server frames are not masked.
                    out.write(byteArrayOf(0x89.toByte(), 0)) // ping
                    val hello = """{"event":"ms.channel.connect"}""".toByteArray()
                    out.write(byteArrayOf(0x81.toByte(), hello.size.toByte()) + hello)
                    out.flush()
                    // Expect the pong, then a masked text frame from the client.
                    repeat(2) {
                        val b0 = input.read()
                        val b1 = input.read()
                        val len = b1 and 0x7F
                        val mask = ByteArray(4) { input.read().toByte() }
                        val payload = ByteArray(len) { (input.read() xor mask[it % 4].toInt()).toByte() }
                        if (b0 and 0x0F == 0xA) gotPong = true else clientSaid = String(payload)
                    }
                }
            }
            val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
            val ws = WebSocketClient.open(socket, "127.0.0.1:${server.localPort}", "/api/v2/channels/samsung.remote.control")
            assertEquals("""{"event":"ms.channel.connect"}""", ws.receiveText())
            ws.sendText("""{"method":"ms.remote.control"}""")
            tv.join(5_000)
            ws.close()
            assertTrue(gotPong)
            assertEquals("""{"method":"ms.remote.control"}""", clientSaid)
        }
    }
}
