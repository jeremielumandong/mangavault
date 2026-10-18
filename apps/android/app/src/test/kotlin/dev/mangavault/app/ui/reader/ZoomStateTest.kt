package dev.mangavault.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    private fun state(content: IntSize = IntSize.Zero) = ZoomState().apply {
        viewport = IntSize(1000, 2000)
        this.content = content
    }

    @Test
    fun zoomedPageReachesEveryEdge() {
        val z = state(IntSize(1000, 1400))
        z.zoomBy(2f, Offset(500f, 1000f))
        // 2000x2800 drawn in a 1000x2000 viewport: 500 sideways and 400 up and down to spare.
        assertEquals(Offset(500f, 400f), z.limits())
        z.panBy(Offset(10_000f, 10_000f))
        assertEquals(Offset(500f, 400f), z.offset)
        z.panBy(Offset(-10_000f, -10_000f))
        assertEquals(Offset(-500f, -400f), z.offset)
    }

    @Test
    fun tallPageStartsAtTopAndPansAtOneX() {
        val z = state(IntSize(1000, 3000))
        assertEquals(Offset(0f, 500f), z.offset)
        assertTrue(z.canPan(Offset(0f, -20f)))
        assertFalse(z.canPan(Offset(0f, 20f)))
        // Sideways swipes are left to the pager.
        assertFalse(z.canPan(Offset(20f, 0f)))
        assertEquals(Offset(0f, -300f), z.panBy(Offset(0f, -300f)))
    }

    @Test
    fun pinchKeepsPointUnderFingers() {
        val z = state(IntSize(1000, 2000))
        val at = Offset(800f, 300f)
        val before = (at - Offset(500f, 1000f) - z.offset) / z.scale
        z.zoomBy(2.5f, at)
        val after = (at - Offset(500f, 1000f) - z.offset) / z.scale
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test
    fun webtoonScrollsListThenRunsPastItsEnds() {
        var listPos = 100f
        val z = state().apply {
            scrollList = { dy ->
                // The list can scroll up (content down) by at most its position.
                val took = if (dy > 0) minOf(dy, listPos) else dy
                listPos -= took
                took
            }
        }
        z.zoomBy(2f, Offset(500f, 1000f))
        assertEquals(Offset(0f, 250f), z.panBy(Offset(0f, 250f)))
        assertEquals(0f, listPos, 0f)
        // Past the top, the zoomed strip's own top edge comes into view.
        assertEquals(150f, z.offset.y, 0.01f)
        // Heading back down gives that back before the list moves.
        z.panBy(Offset(0f, -100f))
        assertEquals(50f, z.offset.y, 0.01f)
        assertEquals(0f, listPos, 0f)
    }

    @Test
    fun resetReturnsToFit() {
        val z = state(IntSize(1000, 1400))
        z.zoomBy(3f, Offset(100f, 100f))
        z.reset()
        assertEquals(1f, z.scale)
        assertEquals(Offset.Zero, z.offset)
        assertFalse(z.zoomed)
    }
}
