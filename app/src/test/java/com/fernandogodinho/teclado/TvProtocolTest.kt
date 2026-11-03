package com.fernandogodinho.teclado

import com.fernandogodinho.teclado.core.LocalNetwork
import com.fernandogodinho.teclado.tv.AtvProtocol
import com.fernandogodinho.teclado.tv.Proto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress

/**
 * The expected bytes were produced by the official protobuf library from the Android TV remote
 * protocol definitions (polo.proto / remotemessage.proto), so these tests pin the wire format.
 */
class TvProtocolTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun pairingMessagesMatchTheReferenceEncoding() {
        assertEquals("080210c80152140a0961747672656d6f746512075465636c61646f", AtvProtocol.pairingRequest("Teclado").hex())
        assertEquals("080210c801a201080a04080310061801", AtvProtocol.pairingOptions().hex())
        assertEquals("080210c801f201080a04080310061001", AtvProtocol.pairingConfiguration().hex())
        assertEquals(
            "080210c801c202220a20000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            AtvProtocol.pairingSecret(ByteArray(32) { it.toByte() }).hex(),
        )
    }

    @Test
    fun remoteMessagesMatchTheReferenceEncoding() {
        assertEquals("0a2208e704121d0a014d12015618012201312a0961747672656d6f74653205312e302e30", AtvProtocol.configure(615, "M", "V").hex())
        assertEquals("120308e704", AtvProtocol.setActive(615).hex())
        assertEquals("4a020807", AtvProtocol.pingResponse(7).hex())
        assertEquals("520408421003", AtvProtocol.key(AtvProtocol.KEY_ENTER).hex())
        assertEquals("aa0117080310051a110801120d080610061a076e6f76656c6173", AtvProtocol.setText("novelas", 3, 5).hex())
    }

    @Test
    fun parsesWhatTheTvSends() {
        assertEquals(AtvProtocol.Incoming.TextField("Pesquisar", "abc", ""), AtvProtocol.parseRemote(hex("b20112121012036162633209506573717569736172")))
        assertEquals(AtvProtocol.Incoming.Ping(42), AtvProtocol.parseRemote(hex("4204082a1001")))
        assertEquals(AtvProtocol.Incoming.Configure(1023, "TV", "TCL"), AtvProtocol.parseRemote(hex("0a0e08ff0712090a025456120354434c")))
        assertEquals(AtvProtocol.PairingReply.OPTIONS, AtvProtocol.parsePairing(AtvProtocol.pairingOptions()))
        assertEquals(AtvProtocol.PairingReply.ERROR, AtvProtocol.parsePairing(hex("0802109003")))
    }

    @Test
    fun pairingSecretFollowsTheProtocol() {
        val cn = BigInteger("C3" + "AB".repeat(127), 16)
        val sn = BigInteger("D1" + "5A".repeat(127), 16)
        val e = BigInteger.valueOf(65537)
        val secret = AtvProtocol.pairingSecret(cn, e, sn, e, "45beef")
        assertEquals("452c2801cfcc429757b2667b416411d0921bf0fc3e01af74d0d9f417c957983a", secret!!.hex())
        // A wrong first byte means the user typed the code wrong.
        assertNull(AtvProtocol.pairingSecret(cn, e, sn, e, "44BEEF"))
        assertNull(AtvProtocol.pairingSecret(cn, e, sn, e, "12345"))
    }

    @Test
    fun framesRoundTrip() {
        val out = ByteArrayOutputStream()
        val big = ByteArray(300) { 7 }
        Proto.writeFrame(out, AtvProtocol.key(AtvProtocol.KEY_BACK))
        Proto.writeFrame(out, big)
        val input = ByteArrayInputStream(out.toByteArray())
        assertArrayEquals(AtvProtocol.key(AtvProtocol.KEY_BACK), Proto.readFrame(input))
        assertArrayEquals(big, Proto.readFrame(input))
    }

    @Test
    fun onlyLocalAddressesAreAllowed() {
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("192.168.0.42")))
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("10.1.2.3")))
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("172.20.0.9")))
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("fe80::1")))
        assertTrue(LocalNetwork.isLocal(InetAddress.getByName("fd12:3456::1")))
        assertFalse(LocalNetwork.isLocal(InetAddress.getByName("8.8.8.8")))
        assertFalse(LocalNetwork.isLocal(InetAddress.getByName("172.32.0.1")))
        assertFalse(LocalNetwork.isLocal(InetAddress.getByName("127.0.0.1")))
        assertFalse(LocalNetwork.isLocal(InetAddress.getByName("2001:4860::8888")))
    }
}
