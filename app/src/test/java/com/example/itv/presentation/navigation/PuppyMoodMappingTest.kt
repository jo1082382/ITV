package com.example.itv.presentation.navigation

import com.example.itv.domain.records.DailyHealthTier
import com.example.itv.presentation.components.PuppyMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Inflater
import kotlin.math.abs

class PuppyMoodMappingTest {

    @Test
    fun `maps all five graded calendar tiers to matching puppy moods and back`() {
        val pairs = listOf(
            DailyHealthTier.GOLD to PuppyMood.BRIGHT,
            DailyHealthTier.SILVER to PuppyMood.SMILE,
            DailyHealthTier.BRONZE to PuppyMood.SAD,
            DailyHealthTier.WARNING to PuppyMood.ANGRY,
            DailyHealthTier.CRITICAL to PuppyMood.SICK
        )

        assertEquals(PuppyMood.entries.size, pairs.size)
        pairs.forEach { (tier, mood) ->
            assertEquals(mood, tier.toPuppyMoodOrNull())
            assertEquals(tier, mood.toHealthTier())
        }
    }

    @Test
    fun `returns null mood for ungraded calendar tiers and null`() {
        assertNull(DailyHealthTier.IN_PROGRESS.toPuppyMoodOrNull())
        assertNull(DailyHealthTier.NO_DATA.toPuppyMoodOrNull())
        assertNull((null as DailyHealthTier?).toPuppyMoodOrNull())
    }

    @Test
    fun `face wipe rectangle boundary lies strictly inside pure white fur with zero clipped outlines or features`() {
        val file = File("src/main/res/drawable-nodpi/itv_puppy_mascot.png")
        if (!file.exists()) return

        val bytes = file.readBytes()
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        dis.skipBytes(8)
        var w = 0
        var h = 0
        var colorType = 0
        val idat = ByteArrayOutputStream()
        while (dis.available() > 0) {
            val len = dis.readInt()
            val typeBytes = ByteArray(4)
            dis.readFully(typeBytes)
            val type = String(typeBytes)
            val data = ByteArray(len)
            dis.readFully(data)
            dis.readInt()
            when (type) {
                "IHDR" -> {
                    val bb = ByteBuffer.wrap(data)
                    w = bb.int
                    h = bb.int
                    bb.get()
                    colorType = bb.get().toInt() and 0xFF
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val bpp = if (colorType == 6) 4 else 3
        val stride = w * bpp
        val inf = Inflater().apply { setInput(idat.toByteArray()) }
        val raw = ByteArray((stride + 1) * h)
        var off = 0
        while (!inf.finished() && off < raw.size) {
            val n = inf.inflate(raw, off, raw.size - off)
            if (n <= 0) break
            off += n
        }
        inf.end()

        val recon = ByteArray(stride * h)
        fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = abs(p - a)
            val pb = abs(p - b)
            val pc = abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
        for (y in 0 until h) {
            val f = raw[y * (stride + 1)].toInt() and 0xFF
            val rowOff = y * stride
            val srcOff = y * (stride + 1) + 1
            for (x in 0 until stride) {
                val cur = raw[srcOff + x].toInt() and 0xFF
                val a = if (x >= bpp) recon[rowOff + x - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) recon[rowOff - stride + x].toInt() and 0xFF else 0
                val c = if (x >= bpp && y > 0) recon[rowOff - stride + x - bpp].toInt() and 0xFF else 0
                recon[rowOff + x] = when (f) {
                    0 -> cur
                    1 -> (cur + a) and 0xFF
                    2 -> (cur + b) and 0xFF
                    3 -> (cur + ((a + b) ushr 1)) and 0xFF
                    4 -> (cur + paeth(a, b, c)) and 0xFF
                    else -> cur
                }.toByte()
            }
        }

        fun isPureWhiteFur(x: Int, y: Int): Boolean {
            val i = y * stride + x * bpp
            val r = recon[i].toInt() and 0xFF
            val g = recon[i + 1].toInt() and 0xFF
            val b = recon[i + 2].toInt() and 0xFF
            val a = if (bpp == 4) recon[i + 3].toInt() and 0xFF else 255
            return a >= 250 && r >= 245 && g >= 245 && b >= 235
        }

        fun pixelHex(x: Int, y: Int): String {
            val i = y * stride + x * bpp
            val r = recon[i].toInt() and 0xFF
            val g = recon[i + 1].toInt() and 0xFF
            val b = recon[i + 2].toInt() and 0xFF
            val a = if (bpp == 4) recon[i + 3].toInt() and 0xFF else 255
            return "#%02X%02X%02X(a%d)".format(r, g, b, a)
        }

        val left = 178
        val right = 384
        val top = 188
        val bottom = 390
        val bad = mutableListOf<String>()
        for (x in left..right) {
            if (!isPureWhiteFur(x, top)) bad.add("T($x,$top)=${pixelHex(x, top)}")
            if (!isPureWhiteFur(x, bottom)) bad.add("B($x,$bottom)=${pixelHex(x, bottom)}")
        }
        for (y in top..bottom) {
            if (!isPureWhiteFur(left, y)) bad.add("L($left,$y)=${pixelHex(left, y)}")
            if (!isPureWhiteFur(right, y)) bad.add("R($right,$y)=${pixelHex(right, y)}")
        }
        assertTrue("Non-white border pixels (${bad.size}): ${bad.take(30).joinToString()}", bad.isEmpty())
    }

    @Test
    fun `verify wipe boxes for all four motion pose sprites`() {
        data class RectBox(val left: Int, val right: Int, val top: Int, val bottom: Int)
        data class Spec(val name: String, val boxes: List<RectBox>)
        val specs = listOf(
            Spec(
                "itv_gallop_front_00.png",
                listOf(
                    RectBox(left = 138, right = 264, top = 105, bottom = 144),
                    RectBox(left = 148, right = 256, top = 140, bottom = 160),
                    RectBox(left = 152, right = 246, top = 156, bottom = 185)
                )
            ),
            Spec(
                "itv_motion_sad.png",
                listOf(
                    RectBox(left = 154, right = 252, top = 86, bottom = 106),
                    RectBox(left = 138, right = 252, top = 104, bottom = 126),
                    RectBox(left = 126, right = 252, top = 124, bottom = 154),
                    RectBox(left = 146, right = 246, top = 150, bottom = 175)
                )
            ),
            Spec(
                "itv_motion_angry.png",
                listOf(
                    RectBox(left = 134, right = 252, top = 112, bottom = 183)
                )
            ),
            Spec(
                "itv_motion_sleep.png",
                listOf(
                    RectBox(left = 208, right = 254, top = 290, bottom = 316),
                    RectBox(left = 272, right = 314, top = 314, bottom = 338)
                )
            )
        )
        val failures = mutableListOf<String>()
        for (spec in specs) {
            val file = File("src/main/res/drawable-nodpi/${spec.name}")
            if (!file.exists()) continue
            val bytes = file.readBytes()
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            dis.skipBytes(8)
            var w = 0; var h = 0; var ct = 0
            val idat = ByteArrayOutputStream()
            while (dis.available() > 0) {
                val len = dis.readInt()
                val tb = ByteArray(4); dis.readFully(tb)
                val t = String(tb)
                val d = ByteArray(len); dis.readFully(d)
                dis.readInt()
                if (t == "IHDR") { val bb = ByteBuffer.wrap(d); w = bb.int; h = bb.int; bb.get(); ct = bb.get().toInt() and 0xFF }
                else if (t == "IDAT") idat.write(d)
                else if (t == "IEND") break
            }
            val bpp = if (ct == 6) 4 else 3
            val stride = w * bpp
            val inf = Inflater().apply { setInput(idat.toByteArray()) }
            val raw = ByteArray((stride + 1) * h)
            var off = 0
            while (!inf.finished() && off < raw.size) {
                val n = inf.inflate(raw, off, raw.size - off)
                if (n <= 0) break
                off += n
            }
            inf.end()
            val recon = ByteArray(stride * h)
            fun paeth(a: Int, b: Int, c: Int): Int {
                val p = a + b - c
                val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
                return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
            }
            for (y in 0 until h) {
                val f = raw[y * (stride + 1)].toInt() and 0xFF
                val ro = y * stride; val so = y * (stride + 1) + 1
                for (x in 0 until stride) {
                    val cur = raw[so + x].toInt() and 0xFF
                    val a = if (x >= bpp) recon[ro + x - bpp].toInt() and 0xFF else 0
                    val b = if (y > 0) recon[ro - stride + x].toInt() and 0xFF else 0
                    val c = if (x >= bpp && y > 0) recon[ro - stride + x - bpp].toInt() and 0xFF else 0
                    recon[ro + x] = when (f) {
                        0 -> cur; 1 -> (cur + a) and 0xFF; 2 -> (cur + b) and 0xFF
                        3 -> (cur + ((a + b) ushr 1)) and 0xFF; 4 -> (cur + paeth(a, b, c)) and 0xFF
                        else -> cur
                    }.toByte()
                }
            }
            fun isFur(x: Int, y: Int): Boolean {
                val i = y * stride + x * bpp
                val r = recon[i].toInt() and 0xFF
                val g = recon[i + 1].toInt() and 0xFF
                val b = recon[i + 2].toInt() and 0xFF
                val a = if (bpp == 4) recon[i + 3].toInt() and 0xFF else 255
                return a >= 245 && r >= 230 && g >= 230 && b >= 225
            }
            fun hex(x: Int, y: Int): String {
                val i = y * stride + x * bpp
                return "#%02X%02X%02X".format(recon[i].toInt() and 0xFF, recon[i + 1].toInt() and 0xFF, recon[i + 2].toInt() and 0xFF)
            }
            fun insideUnion(x: Int, y: Int): Boolean =
                spec.boxes.any { x in it.left..it.right && y in it.top..it.bottom }

            val bad = mutableListOf<String>()
            // Check every outer boundary pixel of the union of boxes
            for (box in spec.boxes) {
                for (x in box.left..box.right) {
                    if (!insideUnion(x, box.top - 1) && !isFur(x, box.top)) bad.add("T($x,${box.top})=${hex(x, box.top)}")
                    if (!insideUnion(x, box.bottom + 1) && !isFur(x, box.bottom)) bad.add("B($x,${box.bottom})=${hex(x, box.bottom)}")
                }
                for (y in box.top..box.bottom) {
                    if (!insideUnion(box.left - 1, y) && !isFur(box.left, y)) bad.add("L(${box.left},$y)=${hex(box.left, y)}")
                    if (!insideUnion(box.right + 1, y) && !isFur(box.right, y)) bad.add("R(${box.right},$y)=${hex(box.right, y)}")
                }
            }
            if (bad.isNotEmpty()) {
                failures.add("${spec.name} (${bad.size}): ${bad.take(12).joinToString()}")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
