package dev.mangavault.core.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A panel as fractions of the page, 0..1 on each axis. */
data class PanelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        val FULL = PanelRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Finds the panels of a comic or manga page for guided view.
 *
 * Works on a small copy of the page (about [ANALYSIS_SIZE] px on the long side). The page colour
 * is sampled from the corners, so white and black gutters both work. The page is split by
 * recursive cuts along gutters: rows of panels first, then the panels in each row, then any
 * stacked panels inside those, which also gives the reading order (left to right for comics,
 * right to left for manga). A gutter has to run clear to both ends, so a white sky inside a
 * bordered panel doesn't split it.
 *
 * The result always ends with the whole page. A page with no clear panels (a splash page,
 * full-bleed art) is just the whole page.
 */
object PanelDetector {
    const val ANALYSIS_SIZE = 500

    /** How close (sum of RGB differences) a pixel must be to the page colour to count as gutter. */
    private const val GUTTER_DISTANCE = 72
    private const val MARGIN_DISTANCE = 40
    /** Share of a line that must be gutter. Balloons and lettering may cross a gutter a little. */
    private const val LINE_COVERAGE = 0.9f
    /** Each end of a gutter line must be clear too, so a panel's own border stops it. */
    private const val END_COVERAGE = 0.9f
    private const val MIN_GUTTER = 2
    /** Pieces thinner than this share of the page are lettering or noise, not panels. */
    private const val MIN_PANEL = 0.06f
    private const val MAX_PANELS = 12
    private const val MAX_DEPTH = 5

    /** [argb] is row-major, one ARGB int per pixel, as Android's Bitmap.getPixels gives. */
    fun detect(argb: IntArray, width: Int, height: Int, rtl: Boolean = false): List<PanelRect> {
        if (width <= 0 || height <= 0 || argb.size < width * height) return listOf(PanelRect.FULL)
        val img = Thumb.of(argb, width, height)
        return detect(img, rtl)
    }

    /** [rgba] is row-major, four bytes per pixel. */
    fun detectRgba(rgba: ByteArray, width: Int, height: Int, rtl: Boolean = false): List<PanelRect> {
        if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return listOf(PanelRect.FULL)
        val argb = IntArray(width * height) { i ->
            val o = i * 4
            (0xFF shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
        }
        return detect(argb, width, height, rtl)
    }

    private fun detect(img: Thumb, rtl: Boolean): List<PanelRect> {
        val bg = img.cornerColour()
        val gutter = BooleanArray(img.w * img.h) { img.distance(img.px[it], bg) < GUTTER_DISTANCE }
        val margin = BooleanArray(img.w * img.h) { img.distance(img.px[it], bg) < MARGIN_DISTANCE }
        val page = Grid(img.w, img.h, gutter)
        val content = Grid(img.w, img.h, margin).trim(Box(0, 0, img.w, img.h), 0.95f) ?: return listOf(PanelRect.FULL)
        // Margins that eat most of the page mean the "page colour" is really the art's.
        if (content.w < img.w / 2 || content.h < img.h / 2) return listOf(PanelRect.FULL)

        val minW = max(4, (img.w * MIN_PANEL).toInt())
        val minH = max(4, (img.h * MIN_PANEL).toInt())
        val found = page.cut(content, horizontalFirst = true, rtl = rtl, depth = 0, minW = minW, minH = minH)
        if (found.size <= 1 || found.size > MAX_PANELS) return listOf(PanelRect.FULL)
        return found.map {
            PanelRect(it.x0 / img.w.toFloat(), it.y0 / img.h.toFloat(), it.x1 / img.w.toFloat(), it.y1 / img.h.toFloat())
        } + PanelRect.FULL
    }

    /** Half-open pixel box. */
    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    private class Grid(val w: Int, val h: Int, val on: BooleanArray) {
        private fun row(y: Int, x0: Int, x1: Int) = (x0 until x1).count { on[y * w + it] }
        private fun col(x: Int, y0: Int, y1: Int) = (y0 until y1).count { on[it * w + x] }

        /** Shrinks [b] while its outer lines are almost all [on]. Null if nothing is left. */
        fun trim(b: Box, coverage: Float): Box? {
            var (x0, y0, x1, y1) = b
            while (y0 < y1 && row(y0, x0, x1) >= (x1 - x0) * coverage) y0++
            while (y1 > y0 && row(y1 - 1, x0, x1) >= (x1 - x0) * coverage) y1--
            while (x0 < x1 && col(x0, y0, y1) >= (y1 - y0) * coverage) x0++
            while (x1 > x0 && col(x1 - 1, y0, y1) >= (y1 - y0) * coverage) x1--
            return if (x1 - x0 < 2 || y1 - y0 < 2) null else Box(x0, y0, x1, y1)
        }

        /** Whether the line at [at] across [from, to) is a gutter, along rows when [horizontal]. */
        private fun isGutter(horizontal: Boolean, at: Int, from: Int, to: Int): Boolean {
            val len = to - from
            val end = max(2, len * 3 / 100)
            fun count(a: Int, b: Int) = if (horizontal) row(at, a, b) else col(at, a, b)
            if (count(from, to) < len * LINE_COVERAGE) return false
            return count(from, from + end) >= end * END_COVERAGE && count(to - end, to) >= end * END_COVERAGE
        }

        /** Runs of gutter lines inside [b], as [start, end) along the cut axis. */
        private fun gutters(b: Box, horizontal: Boolean): List<IntRange> {
            val runs = mutableListOf<IntRange>()
            val (lo, hi) = if (horizontal) b.y0 to b.y1 else b.x0 to b.x1
            val (from, to) = if (horizontal) b.x0 to b.x1 else b.y0 to b.y1
            var start = -1
            for (i in lo..hi) {
                val g = i < hi && isGutter(horizontal, i, from, to)
                if (g && start < 0) start = i
                if (!g && start >= 0) {
                    if (i - start >= MIN_GUTTER && start > lo && i < hi) runs += start until i
                    start = -1
                }
            }
            return runs
        }

        private fun split(b: Box, horizontal: Boolean, runs: List<IntRange>): List<Box> {
            val pieces = mutableListOf<Box>()
            var prev = if (horizontal) b.y0 else b.x0
            for (r in runs + listOf((if (horizontal) b.y1 else b.x1)..Int.MAX_VALUE)) {
                pieces += if (horizontal) Box(b.x0, prev, b.x1, r.first) else Box(prev, b.y0, r.first, b.y1)
                prev = r.last + 1
            }
            return pieces
        }

        fun cut(b: Box, horizontalFirst: Boolean, rtl: Boolean, depth: Int, minW: Int, minH: Int): List<Box> {
            if (depth >= MAX_DEPTH) return listOf(b)
            for (horizontal in listOf(horizontalFirst, !horizontalFirst)) {
                val runs = gutters(b, horizontal)
                if (runs.isEmpty()) continue
                var pieces = split(b, horizontal, runs)
                    .mapNotNull { trim(it, 0.98f) }
                    .filter { it.w >= minW && it.h >= minH }
                if (!horizontal && rtl) pieces = pieces.reversed()
                if (pieces.isEmpty()) return listOf(b)
                // A caption strip was dropped: what's left may still split the other way.
                if (pieces.size == 1) return cut(pieces[0], !horizontal, rtl, depth + 1, minW, minH)
                return pieces.flatMap { cut(it, !horizontal, rtl, depth + 1, minW, minH) }
            }
            return listOf(b)
        }
    }

    /** The page scaled down to at most [ANALYSIS_SIZE] px on its long side. */
    private class Thumb(val px: IntArray, val w: Int, val h: Int) {
        fun cornerColour(): Int {
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (dx in 0..1) for (dy in 0..1) {
                for ((x, y) in listOf(dx to dy, w - 1 - dx to dy, dx to h - 1 - dy, w - 1 - dx to h - 1 - dy)) {
                    val p = px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
            return (r / n shl 16) or (g / n shl 8) or (b / n)
        }

        fun distance(p: Int, q: Int): Int =
            abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                abs((p and 0xFF) - (q and 0xFF))

        companion object {
            fun of(argb: IntArray, width: Int, height: Int): Thumb {
                val long = max(width, height)
                if (long <= ANALYSIS_SIZE) return Thumb(argb, width, height)
                val s = ANALYSIS_SIZE.toFloat() / long
                val w = max(1, (width * s).toInt())
                val h = max(1, (height * s).toInt())
                val out = IntArray(w * h)
                for (y in 0 until h) {
                    val sy = min(height - 1, y * height / h)
                    for (x in 0 until w) out[y * w + x] = argb[sy * width + min(width - 1, x * width / w)]
                }
                return Thumb(out, w, h)
            }
        }
    }
}
