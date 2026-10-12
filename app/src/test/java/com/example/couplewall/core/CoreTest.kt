package com.example.couplewall.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CoreTest {
    private fun stroke(id: Long, n: Int, rnd: Random) = Stroke(
        id, 1_700_000_000_000L + id, 7, 0xFFFF69A0L.toInt(), 0.012f,
        FloatArray(n) { rnd.nextFloat() }, FloatArray(n) { rnd.nextFloat() }
    )

    @Test fun base64RoundTrip() {
        val r = Random(1)
        for (len in 0..300) {
            val b = ByteArray(len).also { r.nextBytes(it) }
            assertArrayEquals(b, Base64Url.decode(Base64Url.encode(b)))
        }
        assertNull(Base64Url.decode("not base64!"))
    }

    @Test fun pairCodeNormalizesHumanInput() {
        val code = PairCode.generate()
        assertEquals(PairCode.LENGTH, code.length)
        assertEquals(code, PairCode.normalize(PairCode.format(code).lowercase()))
        assertEquals(code, PairCode.normalize("  " + PairCode.format(code) + " "))
        assertNull(PairCode.normalize("short"))
        assertNull(PairCode.normalize("U".repeat(20)))
    }

    @Test fun authTokenIsStableHexAndIndependent() {
        val a = PairCode.generate()
        val b = PairCode.generate()
        val t = PairCode.authToken(a)
        assertEquals(64, t.length)
        assertTrue(t.all { it in "0123456789abcdef" })
        assertEquals(t, PairCode.authToken(a))
        assertTrue(t != PairCode.authToken(b))
        assertTrue(t != PairCode.topic(a).removePrefix("cw"))
    }

    @Test fun topicAndKeyAreStableAndDifferent() {
        val a = PairCode.generate()
        val b = PairCode.generate()
        assertEquals(PairCode.topic(a), PairCode.topic(a))
        assertTrue(PairCode.topic(a) != PairCode.topic(b))
        assertEquals(32, PairCode.key(a).size)
        assertTrue(PairCode.topic(a).length <= 64)
    }

    @Test fun cryptoRoundTripAndTamperDetection() {
        val key = PairCode.key(PairCode.generate())
        val plain = "hello couple".toByteArray()
        val sealed = Crypto.seal(key, plain)
        assertArrayEquals(plain, Crypto.open(key, sealed))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(Crypto.open(key, sealed))
        assertNull(Crypto.open(PairCode.key(PairCode.generate()), Crypto.seal(key, plain)))
    }

    @Test fun strokesRoundTrip() {
        val key = PairCode.key(PairCode.generate())
        val r = Random(2)
        val list = listOf(stroke(1, 5, r), stroke(2, 120, r), stroke(3, 1, r))
        val msg = Protocol.open(Protocol.seal(Message.Strokes(list), key), key)
        assertNotNull(msg)
        val got = (msg as Message.Strokes).strokes
        assertEquals(3, got.size)
        for (i in list.indices) {
            assertEquals(list[i].id, got[i].id)
            assertEquals(list[i].time, got[i].time)
            assertEquals(list[i].color, got[i].color)
            assertEquals(list[i].author, got[i].author)
            assertEquals(list[i].size, got[i].size)
            assertEquals(list[i].width, got[i].width, 0.0002f)
            for (k in 0 until list[i].size) {
                assertEquals(list[i].xs[k], got[i].xs[k], 1.0f / 65535f)
                assertEquals(list[i].ys[k], got[i].ys[k], 1.0f / 65535f)
            }
        }
    }

    @Test fun clearAndRemoveRoundTrip() {
        val key = PairCode.key(PairCode.generate())
        val clear = Protocol.open(Protocol.seal(Message.Clear(123456789L, 42), key), key) as Message.Clear
        assertEquals(123456789L, clear.time)
        assertEquals(42, clear.author)
        val rm = Protocol.open(Protocol.seal(Message.Remove(listOf(5L, 6L, 7L)), key), key) as Message.Remove
        assertEquals(listOf(5L, 6L, 7L), rm.ids)
    }

    @Test fun wrongKeyOrGarbageIsRejected() {
        val key = PairCode.key(PairCode.generate())
        val other = PairCode.key(PairCode.generate())
        val text = Protocol.seal(Message.Clear(1L, 1), key)
        assertNull(Protocol.open(text, other))
        assertNull(Protocol.open("garbage!!", key))
        assertNull(Protocol.open("AAAA", key))
    }

    @Test fun packRespectsTheRelayLimit() {
        val key = PairCode.key(PairCode.generate())
        val r = Random(3)
        val strokes = (1L..40L).map { stroke(it, Protocol.MAX_POINTS, r) }
        val packed = Protocol.pack(strokes, key)
        assertTrue(packed.size > 1)
        var total = 0
        for (p in packed) {
            assertTrue("text too long: ${p.text.length}", p.text.length <= Protocol.MAX_TEXT)
            val decoded = Protocol.open(p.text, key) as Message.Strokes
            assertEquals(p.strokes.size, decoded.strokes.size)
            total += decoded.strokes.size
        }
        assertEquals(40, total)
    }

    @Test fun rdpKeepsEndpointsAndReduces() {
        val n = 200
        val xs = FloatArray(n) { it / (n - 1f) }
        val ys = FloatArray(n) { 0.5f }
        val (sx, sy) = Simplify.rdp(xs, ys, 0.001f)
        assertEquals(2, sx.size)
        assertEquals(0f, sx[0], 0f)
        assertEquals(1f, sx[1], 0f)
        assertEquals(0.5f, sy[0], 0f)
        val (lx, _) = Simplify.limit(FloatArray(n) { it / (n - 1f) }, FloatArray(n) { (it % 7) / 70f }, 0.0001f, 40)
        assertTrue(lx.size <= 40)
    }
}
