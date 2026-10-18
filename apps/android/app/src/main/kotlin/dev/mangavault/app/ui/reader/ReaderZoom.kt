package dev.mangavault.app.ui.reader

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

/**
 * Pinch, pan, fling and double-tap zoom for reader pages.
 *
 * The content is laid out centred in the [viewport] at [content] size (which may be larger than
 * the viewport, as a tall page at fit-width is) and drawn scaled by [scale] around its centre,
 * then moved by [offset]. Pan limits come from the scaled content, so every edge can be reached.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport = IntSize.Zero
        set(v) {
            field = v
            clamp()
        }

    /** Unscaled size of the content. Zero means it fills the viewport. */
    var content = IntSize.Zero
        set(v) {
            if (field == v) return
            field = v
            if (!touched) offset = Offset(0f, limits().y) else clamp()
        }

    /**
     * Webtoon mode: scrolls the list underneath by a screen-pixel delta and returns how much it
     * took. Vertical pans go to the list first and to [offset] only past the list's ends.
     */
    var scrollList: ((Float) -> Float)? = null

    val zoomed get() = scale > 1.01f
    private var touched = false
    private val mutex = MutatorMutex()

    /** Largest |offset| on each axis at [s]. */
    fun limits(s: Float = scale): Offset {
        val c = if (content == IntSize.Zero) viewport else content
        return Offset(max(0f, (c.width * s - viewport.width) / 2f), max(0f, (c.height * s - viewport.height) / 2f))
    }

    private fun clamp() {
        val l = limits()
        offset = Offset(offset.x.coerceIn(-l.x, l.x), offset.y.coerceIn(-l.y, l.y))
    }

    /** Whether a drag of [d] would move the content along its main axis. */
    fun canPan(d: Offset): Boolean {
        val l = limits()
        return if (abs(d.x) >= abs(d.y)) {
            l.x > 0f && if (d.x > 0f) offset.x < l.x else offset.x > -l.x
        } else {
            scrollList != null || (l.y > 0f && if (d.y > 0f) offset.y < l.y else offset.y > -l.y)
        }
    }

    /** Moves the content by [d] screen pixels; returns how far it actually went on each axis. */
    fun panBy(d: Offset): Offset {
        touched = true
        val start = offset
        var dy = d.y
        var listed = 0f
        val list = scrollList
        if (list != null && dy != 0f) {
            // offset.y only shows the strip's ends: give it back before scrolling the list again.
            if (offset.y != 0f && sign(dy) != sign(offset.y)) {
                val back = if (abs(dy) > abs(offset.y)) -offset.y else dy
                offset = Offset(offset.x, offset.y + back)
                dy -= back
                listed += back
            }
            if (dy != 0f) {
                val took = list(dy)
                dy -= took
                listed += took
            }
        }
        val before = offset
        offset = Offset(offset.x + d.x, offset.y + dy)
        clamp()
        return Offset(offset.x - start.x, offset.y - before.y + listed)
    }

    /** Scales by [factor] keeping the point [at] (viewport pixels) still, then moves by [pan]. */
    fun zoomBy(factor: Float, at: Offset, pan: Offset = Offset.Zero) {
        touched = true
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        val c = at - Offset(viewport.width / 2f, viewport.height / 2f)
        offset = (offset - c) * (newScale / scale) + c
        scale = newScale
        clamp()
        if (pan != Offset.Zero) panBy(pan)
    }

    fun reset() {
        touched = false
        scale = 1f
        offset = Offset(0f, limits(1f).y)
    }

    /** Stops a running fling or double-tap animation. */
    suspend fun stop() {
        mutex.mutate(MutatePriority.UserInput) {}
    }

    /** Toggles [DOUBLE_TAP_SCALE] around [at] (viewport pixels). */
    suspend fun toggle(at: Offset) {
        mutex.mutate {
            val to = if (zoomed) 1f else DOUBLE_TAP_SCALE
            animate(scale, to, animationSpec = tween(220)) { s, _ -> zoomBy(s / scale, at) }
        }
    }

    /** Momentum after a drag, in screen pixels per second. Each axis stops at its edge. */
    suspend fun fling(vx: Float, vy: Float, decay: DecayAnimationSpec<Float>) {
        mutex.mutate {
            coroutineScope {
                launch { flingAxis(vx, decay) { panBy(Offset(it, 0f)).x } }
                launch { flingAxis(vy, decay) { panBy(Offset(0f, it)).y } }
            }
        }
    }

    private suspend fun flingAxis(v: Float, decay: DecayAnimationSpec<Float>, move: (Float) -> Float) {
        if (abs(v) < MIN_FLING) return
        var last = 0f
        AnimationState(0f, v).animateDecay(decay) {
            val d = value - last
            last = value
            if (abs(move(d) - d) > 0.5f) cancelAnimation()
        }
    }

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.2f
        private const val MIN_FLING = 50f
    }
}

/**
 * Pinch-zoom, pan and fling. Two fingers always zoom. One finger pans when zoomed in; at 1× it pans
 * only with [panUnzoomed] and when the content can move that way (a tall page), otherwise the drag
 * is left to the pager or list underneath. Put the taps after this modifier.
 */
fun Modifier.zoomable(state: ZoomState, panUnzoomed: Boolean): Modifier = this
    .onSizeChanged { state.viewport = it }
    .pointerInput(state, panUnzoomed) {
        val decay = splineBasedDecay<Float>(this)
        val maxV = viewConfiguration.maximumFlingVelocity
        val slop = viewConfiguration.touchSlop
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                launch { state.stop() }
                val tracker = VelocityTracker()
                var track = Offset.Zero
                tracker.addPosition(down.uptimeMillis, track)
                var mine = false
                var passed = false
                var travel = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    val pan = event.calculatePan()
                    if (pressed >= 2) {
                        mine = true
                        passed = false
                        state.zoomBy(event.calculateZoom(), event.calculateCentroid(), pan)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else if (pressed == 1 && !passed) {
                        val ch = event.changes.first { it.pressed }
                        if (!mine) {
                            travel += pan
                            if (travel.getDistance() > slop) {
                                mine = state.zoomed || (panUnzoomed && state.canPan(travel))
                                passed = !mine
                            }
                        }
                        if (mine && !ch.isConsumed) {
                            state.panBy(pan)
                            if (ch.positionChanged()) ch.consume()
                        }
                    }
                    track += pan
                    event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, track) }
                } while (event.changes.any { it.pressed })
                if (mine) {
                    val v = tracker.calculateVelocity()
                    launch { state.fling(v.x.coerceIn(-maxV, maxV), v.y.coerceIn(-maxV, maxV), decay) }
                }
            }
        }
    }

/** Draws the content scaled and moved by [state]; reads it in the draw phase only. */
fun Modifier.zoomLayer(state: ZoomState): Modifier = this.graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}
