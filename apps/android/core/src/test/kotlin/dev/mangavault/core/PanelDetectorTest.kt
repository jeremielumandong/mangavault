package dev.mangavault.core

import dev.mangavault.core.util.PanelDetector
import dev.mangavault.core.util.PanelRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PanelDetectorTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()

    /** A [w]×[h] page in [paper] with bordered grey panels at the given pixel boxes. */
    private class Page(val w: Int, val h: Int, paper: Int) {
        val px = IntArray(w * h) { paper }

        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = c
        }

        fun panel(x0: Int, y0: Int, x1: Int, y1: Int, border: Int = 0xFF000000.toInt(), inside: Int = 0xFF808080.toInt()) {
            fill(x0, y0, x1, y1, border)
            fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, inside)
        }
    }

    private fun assertNear(expected: PanelRect, actual: PanelRect) {
        val tol = 0.02f
        assertTrue(
            "expected $expected, got $actual",
            abs(expected.left - actual.left) < tol && abs(expected.top - actual.top) < tol &&
                abs(expected.right - actual.right) < tol && abs(expected.bottom - actual.bottom) < tol,
        )
    }

    @Test
    fun gridReadsRowsThenLeftToRight() {
        val p = Page(400, 600, white)
        p.panel(20, 20, 380, 200) // wide top panel
        p.panel(20, 220, 190, 400)
        p.panel(210, 220, 380, 400)
        p.panel(20, 420, 380, 580)
        val panels = PanelDetector.detect(p.px, p.w, p.h)
        assertEquals(5, panels.size)
        assertNear(PanelRect(0.05f, 0.033f, 0.95f, 0.333f), panels[0])
        assertNear(PanelRect(0.05f, 0.367f, 0.475f, 0.667f), panels[1])
        assertNear(PanelRect(0.525f, 0.367f, 0.95f, 0.667f), panels[2])
        assertNear(PanelRect(0.05f, 0.7f, 0.95f, 0.967f), panels[3])
        assertEquals(PanelRect.FULL, panels.last())
    }

    @Test
    fun rightToLeftForManga() {
        val p = Page(400, 600, white)
        p.panel(20, 20, 190, 290)
        p.panel(210, 20, 380, 290)
        p.panel(20, 310, 380, 580)
        val panels = PanelDetector.detect(p.px, p.w, p.h, rtl = true)
        assertEquals(4, panels.size)
        assertTrue(panels[0].left > 0.5f)
        assertTrue(panels[1].left < 0.1f)
        assertTrue(panels[2].top > 0.5f)
    }

    @Test
    fun tallPanelBesideStackedPanels() {
        // A tall panel on the left, two stacked on the right: no gutter crosses the whole page.
        val p = Page(400, 600, white)
        p.panel(20, 20, 190, 580)
        p.panel(210, 20, 380, 290)
        p.panel(210, 310, 380, 580)
        val panels = PanelDetector.detect(p.px, p.w, p.h)
        assertEquals(4, panels.size)
        assertTrue(panels[0].left < 0.1f && panels[0].bottom > 0.9f)
        assertTrue(panels[1].left > 0.5f && panels[1].bottom < 0.5f)
        assertTrue(panels[2].left > 0.5f && panels[2].top > 0.5f)
    }

    @Test
    fun whiteSkyInsideAPanelDoesNotSplitIt() {
        val p = Page(400, 600, white)
        p.panel(20, 20, 380, 290)
        // A white band across the inside of the first panel, between its borders.
        p.fill(23, 120, 377, 160, white)
        p.panel(20, 310, 380, 580)
        val panels = PanelDetector.detect(p.px, p.w, p.h)
        assertEquals(3, panels.size)
        assertNear(PanelRect(0.05f, 0.033f, 0.95f, 0.483f), panels[0])
    }

    @Test
    fun blackGutters() {
        val p = Page(400, 600, black)
        p.panel(20, 20, 380, 290, border = white, inside = grey)
        p.panel(20, 310, 380, 580, border = white, inside = grey)
        val panels = PanelDetector.detect(p.px, p.w, p.h)
        assertEquals(3, panels.size)
        assertTrue(panels[0].bottom < 0.5f && panels[1].top > 0.5f)
    }

    @Test
    fun splashPageIsJustTheWholePage() {
        val p = Page(400, 600, grey)
        p.fill(100, 100, 300, 500, black)
        assertEquals(listOf(PanelRect.FULL), PanelDetector.detect(p.px, p.w, p.h))
    }

    @Test
    fun largePagesAreScaledDown() {
        val p = Page(1600, 2400, white)
        p.panel(80, 80, 1520, 1160)
        p.panel(80, 1240, 1520, 2320)
        val panels = PanelDetector.detect(p.px, p.w, p.h)
        assertEquals(3, panels.size)
        assertNear(PanelRect(0.05f, 0.033f, 0.95f, 0.483f), panels[0])
    }

    @Test
    fun rgbaBytes() {
        val p = Page(200, 300, white)
        p.panel(10, 10, 190, 140)
        p.panel(10, 160, 190, 290)
        val bytes = ByteArray(p.px.size * 4)
        p.px.forEachIndexed { i, c ->
            bytes[i * 4] = (c shr 16).toByte()
            bytes[i * 4 + 1] = (c shr 8).toByte()
            bytes[i * 4 + 2] = c.toByte()
            bytes[i * 4 + 3] = -1
        }
        assertEquals(3, PanelDetector.detectRgba(bytes, p.w, p.h).size)
    }
}
