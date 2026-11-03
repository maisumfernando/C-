package com.fernandogodinho.teclado.tv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal Protocol Buffers encoding (varints and length-delimited fields), enough for the
 * Android TV remote protocol. Hand-written so the app needs no extra library.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        varint(value)
        return this
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(v: Long) {
        var value = v
        while (true) {
            if (value and 0x7FL.inv() == 0L) {
                out.write(value.toInt())
                return
            }
            out.write(((value and 0x7F) or 0x80).toInt())
            value = value ushr 7
        }
    }
}

/** A decoded message: field number → values (varints as Long, length-delimited as ByteArray). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int): Long = (fields[field]?.firstOrNull() as? Long) ?: 0L
    fun int(field: Int): Int = long(field).toInt()
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun string(field: Int): String = bytes(field)?.toString(Charsets.UTF_8).orEmpty()
    fun message(field: Int): ProtoMessage? = bytes(field)?.let { Proto.decode(it) }
    fun messages(field: Int): List<ProtoMessage> = fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(Proto::decode) }

    companion object {
        val EMPTY = ProtoMessage(emptyMap())
    }
}

object Proto {
    /** Upper bound for one framed message; the TV never sends anything close to this. */
    const val MAX_FRAME = 64 * 1024

    fun decode(data: ByteArray): ProtoMessage {
        val fields = LinkedHashMap<Int, MutableList<Any>>()
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < data.size) { "truncated varint" }
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }
        while (pos < data.size) {
            val key = readVarint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> fields.getOrPut(field) { mutableListOf() }.add(readVarint())
                1 -> pos += 8
                2 -> {
                    val len = readVarint().toInt()
                    require(len >= 0 && pos + len <= data.size) { "bad length" }
                    fields.getOrPut(field) { mutableListOf() }.add(data.copyOfRange(pos, pos + len))
                    pos += len
                }
                5 -> pos += 4
                else -> throw IllegalArgumentException("unsupported wire type")
            }
        }
        return ProtoMessage(fields)
    }

    /** Writes [message] prefixed by its length as a varint (the framing used by the TV). */
    fun writeFrame(out: OutputStream, message: ByteArray) {
        var len = message.size.toLong()
        val prefix = ByteArrayOutputStream()
        while (true) {
            if (len and 0x7FL.inv() == 0L) {
                prefix.write(len.toInt())
                break
            }
            prefix.write(((len and 0x7F) or 0x80).toInt())
            len = len ushr 7
        }
        out.write(prefix.toByteArray() + message)
        out.flush()
    }

    /** Reads one length-prefixed message; throws [EOFException] when the connection closes. */
    fun readFrame(input: InputStream): ByteArray {
        var len = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            require(shift < 35) { "frame length too long" }
        }
        require(len in 0..MAX_FRAME) { "frame too large" }
        val buf = ByteArray(len.toInt())
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return buf
    }
}
