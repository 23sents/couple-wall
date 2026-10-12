package com.example.couplewall.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt

/** What travels over the relay (always sealed with the couple's key). */
sealed class Message {
    class Strokes(val strokes: List<Stroke>) : Message()
    class Clear(val time: Long, val author: Int) : Message()
    class Remove(val ids: List<Long>) : Message()
}

/** A ready-to-send text and the strokes it carries. */
class Packed(val text: String, val strokes: List<Stroke>)

/**
 * Wire format: binary (quantised to 16 bit) -> deflate -> AES-GCM -> base64url text.
 * The relay (ntfy) limits a message to 4096 bytes, so strokes are packed into batches under [MAX_TEXT] characters.
 */
object Protocol {
    const val MAX_TEXT = 3400
    const val MAX_POINTS = 220

    private const val MAGIC = 0x43
    private const val VERSION = 1
    private const val T_STROKES = 1
    private const val T_CLEAR = 2
    private const val T_REMOVE = 3

    fun seal(msg: Message, key: ByteArray): String =
        Base64Url.encode(Crypto.seal(key, deflate(encode(msg))))

    fun open(text: String, key: ByteArray): Message? {
        val sealed = Base64Url.decode(text.trim()) ?: return null
        val packed = Crypto.open(key, sealed) ?: return null
        val raw = inflate(packed) ?: return null
        return decode(raw)
    }

    /** Greedily packs strokes into messages that fit the relay limit. */
    fun pack(strokes: List<Stroke>, key: ByteArray): List<Packed> {
        val out = ArrayList<Packed>()
        var current = ArrayList<Stroke>()
        for (s in strokes) {
            current.add(s)
            if (current.size > 1 && seal(Message.Strokes(current), key).length > MAX_TEXT) {
                current.removeAt(current.size - 1)
                out.add(Packed(seal(Message.Strokes(current), key), current))
                current = arrayListOf(s)
            }
        }
        if (current.isNotEmpty()) out.add(Packed(seal(Message.Strokes(current), key), current))
        return out
    }

    // ------------------------------------------------------------------ binary
    private fun encode(msg: Message): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeByte(MAGIC)
        out.writeByte(VERSION)
        when (msg) {
            is Message.Strokes -> {
                out.writeByte(T_STROKES)
                out.writeShort(msg.strokes.size)
                for (s in msg.strokes) {
                    out.writeLong(s.id)
                    out.writeLong(s.time)
                    out.writeInt(s.author)
                    out.writeInt(s.color)
                    out.writeShort((s.width * 10000f).roundToInt().coerceIn(1, 65535))
                    out.writeShort(s.size)
                    for (i in 0 until s.size) {
                        out.writeShort(q(s.xs[i]))
                        out.writeShort(q(s.ys[i]))
                    }
                }
            }
            is Message.Clear -> {
                out.writeByte(T_CLEAR)
                out.writeLong(msg.time)
                out.writeInt(msg.author)
            }
            is Message.Remove -> {
                out.writeByte(T_REMOVE)
                out.writeShort(msg.ids.size)
                for (id in msg.ids) out.writeLong(id)
            }
        }
        out.flush()
        return bos.toByteArray()
    }

    private fun decode(data: ByteArray): Message? {
        return try {
            val inp = DataInputStream(ByteArrayInputStream(data))
            if (inp.readUnsignedByte() != MAGIC || inp.readUnsignedByte() != VERSION) return null
            when (inp.readUnsignedByte()) {
                T_STROKES -> {
                    val count = inp.readUnsignedShort()
                    val list = ArrayList<Stroke>(count)
                    repeat(count) {
                        val id = inp.readLong()
                        val time = inp.readLong()
                        val author = inp.readInt()
                        val color = inp.readInt()
                        val width = inp.readUnsignedShort() / 10000f
                        val n = inp.readUnsignedShort()
                        if (n > 5000) return null
                        val xs = FloatArray(n)
                        val ys = FloatArray(n)
                        for (i in 0 until n) {
                            xs[i] = inp.readUnsignedShort() / 65535f
                            ys[i] = inp.readUnsignedShort() / 65535f
                        }
                        list.add(Stroke(id, time, author, color, width, xs, ys))
                    }
                    Message.Strokes(list)
                }
                T_CLEAR -> Message.Clear(inp.readLong(), inp.readInt())
                T_REMOVE -> {
                    val count = inp.readUnsignedShort()
                    Message.Remove(List(count) { inp.readLong() })
                }
                else -> null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun q(v: Float): Int = (v.coerceIn(0f, 1f) * 65535f).roundToInt()

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) bos.write(buf, 0, d.deflate(buf))
        d.end()
        return bos.toByteArray()
    }

    private fun inflate(data: ByteArray): ByteArray? {
        val inf = Inflater(true)
        return try {
            inf.setInput(data)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bos.write(buf, 0, n)
                if (bos.size() > 1_000_000) return null
            }
            bos.toByteArray()
        } catch (e: DataFormatException) {
            null
        } finally {
            inf.end()
        }
    }
}
