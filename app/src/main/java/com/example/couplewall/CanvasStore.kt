package com.example.couplewall

import com.example.couplewall.core.Stroke
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * The shared drawing: strokes sorted by time, de-duplicated by id, with tombstones for undone strokes and a
 * "cleared at" watermark. Persisted to a small binary file. All methods are thread-safe.
 */
class CanvasStore(private val file: File) {
    private val strokes = ArrayList<Stroke>()
    private val ids = HashSet<Long>()
    private val tombstones = LinkedHashSet<Long>()
    private var clearedAt = 0L

    @Synchronized fun snapshot(): List<Stroke> = ArrayList(strokes)

    @Synchronized fun isEmpty(): Boolean = strokes.isEmpty()

    @Synchronized fun addLocal(s: Stroke) = insert(s)

    /** Returns true when something new was added. */
    @Synchronized fun addRemote(list: List<Stroke>): Boolean {
        var changed = false
        for (s in list) {
            if (s.time <= clearedAt || s.id in ids || s.id in tombstones) continue
            s.sent = true
            insert(s)
            changed = true
        }
        return changed
    }

    @Synchronized fun remove(removeIds: Collection<Long>): Boolean {
        var changed = false
        for (id in removeIds) {
            tombstones.add(id)
            if (ids.remove(id)) {
                strokes.removeAll { it.id == id }
                changed = true
            }
        }
        while (tombstones.size > MAX_TOMBSTONES) tombstones.remove(tombstones.first())
        return changed
    }

    @Synchronized fun clear(time: Long): Boolean {
        clearedAt = max(clearedAt, time)
        val before = strokes.size
        strokes.removeAll { it.time <= clearedAt }
        ids.clear()
        for (s in strokes) ids.add(s.id)
        return strokes.size != before
    }

    @Synchronized fun latestTime(): Long = strokes.maxOfOrNull { it.time } ?: 0L

    @Synchronized fun unsent(): List<Stroke> = strokes.filter { !it.sent }

    @Synchronized fun markSent(list: Collection<Long>) {
        val set = list.toHashSet()
        for (s in strokes) if (s.id in set) s.sent = true
    }

    @Synchronized fun ownLast(author: Int): Stroke? = strokes.lastOrNull { it.author == author }

    @Synchronized fun reset() {
        strokes.clear(); ids.clear(); tombstones.clear(); clearedAt = 0L
    }

    private fun insert(s: Stroke) {
        strokes.add(s)
        ids.add(s.id)
        strokes.sortWith(compareBy<Stroke>({ it.time }, { it.id }))
    }

    // ------------------------------------------------------------------ persistence
    @Synchronized fun save() {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(MAGIC)
                out.writeLong(clearedAt)
                out.writeInt(strokes.size)
                for (s in strokes) {
                    out.writeBoolean(s.sent)
                    out.writeLong(s.id)
                    out.writeLong(s.time)
                    out.writeInt(s.author)
                    out.writeInt(s.color)
                    out.writeFloat(s.width)
                    out.writeShort(s.size)
                    for (i in 0 until s.size) {
                        out.writeFloat(s.xs[i])
                        out.writeFloat(s.ys[i])
                    }
                }
                out.writeInt(tombstones.size)
                for (t in tombstones) out.writeLong(t)
            }
            tmp.renameTo(file)
        } catch (e: IOException) {
            // disk full or similar: the next save will try again
        }
    }

    @Synchronized fun load() {
        if (!file.exists()) return
        try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                if (inp.readInt() != MAGIC) return
                clearedAt = inp.readLong()
                val count = inp.readInt()
                repeat(count) {
                    val sent = inp.readBoolean()
                    val id = inp.readLong()
                    val time = inp.readLong()
                    val author = inp.readInt()
                    val color = inp.readInt()
                    val width = inp.readFloat()
                    val n = inp.readUnsignedShort()
                    val xs = FloatArray(n)
                    val ys = FloatArray(n)
                    for (i in 0 until n) { xs[i] = inp.readFloat(); ys[i] = inp.readFloat() }
                    val s = Stroke(id, time, author, color, width, xs, ys)
                    s.sent = sent
                    strokes.add(s)
                    ids.add(id)
                }
                val tc = inp.readInt()
                repeat(tc) { tombstones.add(inp.readLong()) }
            }
        } catch (e: IOException) {
            reset()
        }
    }

    private companion object {
        const val MAGIC = 0x43575354
        const val MAX_TOMBSTONES = 3000
    }
}
