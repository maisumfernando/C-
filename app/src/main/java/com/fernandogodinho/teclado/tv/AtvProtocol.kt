package com.fernandogodinho.teclado.tv

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Messages of the Android TV / Google TV remote protocol (v2), the one used by the Google TV
 * app: pairing on port 6467 ("polo" OuterMessage) and remote control on port 6466
 * (RemoteMessage). Field numbers follow the public .proto definitions.
 */
object AtvProtocol {
    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466
    const val SERVICE_TYPE = "_androidtvremote2._tcp."

    private const val PROTOCOL_VERSION = 2
    const val STATUS_OK = 200
    private const val ENCODING_HEXADECIMAL = 3
    private const val ROLE_INPUT = 1

    // Feature flags exchanged in RemoteConfigure / RemoteSetActive.
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_IME = 4
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURE_APP_LINK = 512
    const val FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_IME or FEATURE_POWER or FEATURE_VOLUME or FEATURE_APP_LINK

    // Android key codes understood by the TV.
    const val KEY_HOME = 3
    const val KEY_BACK = 4
    const val KEY_DPAD_UP = 19
    const val KEY_DPAD_DOWN = 20
    const val KEY_DPAD_LEFT = 21
    const val KEY_DPAD_RIGHT = 22
    const val KEY_DPAD_CENTER = 23
    const val KEY_ENTER = 66
    const val KEY_DEL = 67
    private const val DIRECTION_SHORT = 3

    // --- Pairing (OuterMessage) ------------------------------------------------------------

    private fun outer(body: ProtoWriter.() -> Unit) = ProtoWriter()
        .int(1, PROTOCOL_VERSION)
        .int(2, STATUS_OK)
        .apply(body)
        .toByteArray()

    fun pairingRequest(clientName: String) = outer {
        message(10) { string(1, "atvremote"); string(2, clientName) }
    }

    fun pairingOptions() = outer {
        message(20) {
            message(1) { int(1, ENCODING_HEXADECIMAL); int(2, 6) }
            int(3, ROLE_INPUT)
        }
    }

    fun pairingConfiguration() = outer {
        message(30) {
            message(1) { int(1, ENCODING_HEXADECIMAL); int(2, 6) }
            int(2, ROLE_INPUT)
        }
    }

    fun pairingSecret(secret: ByteArray) = outer { message(40) { bytes(1, secret) } }

    enum class PairingReply { REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, ERROR, UNKNOWN }

    fun parsePairing(data: ByteArray): PairingReply {
        val m = Proto.decode(data)
        if (m.int(2) != STATUS_OK) return PairingReply.ERROR
        return when {
            m.has(11) -> PairingReply.REQUEST_ACK
            m.has(20) -> PairingReply.OPTIONS
            m.has(31) -> PairingReply.CONFIGURATION_ACK
            m.has(41) -> PairingReply.SECRET_ACK
            else -> PairingReply.UNKNOWN
        }
    }

    /** Big-endian magnitude without the sign byte that BigInteger.toByteArray() may add. */
    fun unsigned(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    /**
     * The secret that proves to the TV that the user typed the code it displays:
     * SHA-256(client modulus, client exponent, server modulus, server exponent, code[2..]).
     * The first byte of the hash must equal the first two hex digits of the code; returns null
     * when it does not (wrong code).
     */
    fun pairingSecret(
        clientModulus: BigInteger,
        clientExponent: BigInteger,
        serverModulus: BigInteger,
        serverExponent: BigInteger,
        code: String,
    ): ByteArray? {
        val clean = code.trim().uppercase()
        if (!Regex("[0-9A-F]{6}").matches(clean)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(clientModulus))
        digest.update(unsigned(clientExponent))
        digest.update(unsigned(serverModulus))
        digest.update(unsigned(serverExponent))
        digest.update(hex(clean.substring(2)))
        val hash = digest.digest()
        return if ((hash[0].toInt() and 0xFF) == clean.substring(0, 2).toInt(16)) hash else null
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    // --- Remote (RemoteMessage) ------------------------------------------------------------

    fun configure(features: Int, model: String, vendor: String) = ProtoWriter().message(1) {
        int(1, features)
        message(2) {
            string(1, model)
            string(2, vendor)
            int(3, 1)
            string(4, "1")
            string(5, "atvremote")
            string(6, "1.0.0")
        }
    }.toByteArray()

    fun setActive(features: Int) = ProtoWriter().message(2) { int(1, features) }.toByteArray()

    fun pingResponse(value: Int) = ProtoWriter().message(9) { int(1, value) }.toByteArray()

    fun key(code: Int) = ProtoWriter().message(10) { int(1, code); int(2, DIRECTION_SHORT) }.toByteArray()

    /** Replaces the content of the TV's focused text field with [text] (cursor at the end). */
    fun setText(text: String, imeCounter: Int, fieldCounter: Int) = ProtoWriter().message(21) {
        int(1, imeCounter)
        int(2, fieldCounter)
        message(3) {
            int(1, 1)
            message(2) {
                val cursor = (text.length - 1).coerceAtLeast(0)
                int(1, cursor)
                int(2, cursor)
                string(3, text)
            }
        }
    }.toByteArray()

    /** What the TV told us. */
    sealed class Incoming {
        data class Configure(val features: Int, val model: String, val vendor: String) : Incoming()
        object SetActive : Incoming()
        data class Ping(val value: Int) : Incoming()
        /** A text field got focus on the TV (the keyboard would open there). */
        data class TextField(val label: String, val value: String, val app: String) : Incoming()
        data class ImeCounters(val imeCounter: Int, val fieldCounter: Int) : Incoming()
        /** The app in the foreground on the TV changed. */
        data class AppInfo(val app: String) : Incoming()
        data class Power(val on: Boolean) : Incoming()
        object Error : Incoming()
        object Other : Incoming()
    }

    fun parseRemote(data: ByteArray): Incoming {
        val m = Proto.decode(data)
        m.message(1)?.let { c ->
            val info = c.message(2) ?: ProtoMessage.EMPTY
            return Incoming.Configure(c.int(1), info.string(1), info.string(2))
        }
        if (m.has(2)) return Incoming.SetActive
        m.message(8)?.let { return Incoming.Ping(it.int(1)) }
        m.message(22)?.let { show ->
            val field = show.message(2) ?: ProtoMessage.EMPTY
            return Incoming.TextField(label = field.string(6), value = field.string(2), app = "")
        }
        m.message(20)?.let { inject -> return Incoming.AppInfo(inject.message(1)?.string(12).orEmpty()) }
        m.message(21)?.let { return Incoming.ImeCounters(it.int(1), it.int(2)) }
        m.message(40)?.let { return Incoming.Power(it.long(1) != 0L) }
        if (m.has(3)) return Incoming.Error
        return Incoming.Other
    }
}
