package com.dreideas.tappybird.entities

import android.graphics.RectF
import com.dreideas.tappybird.GameConfig

/**
 * A single rectangular pipe (either the upper half or the lower half of a pair).
 *
 * Bounds are in **world units**; GameView applies the world → screen
 * transform at render time. Intentionally a plain data holder with no
 * update logic of its own — [PipePair] owns the scrolling X coordinate,
 * Pipe just exposes bounds.
 */
class Pipe(
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun asRect(out: RectF = RectF()): RectF {
        out.set(left, top, right, bottom)
        return out
    }

    companion object {
        val PIPE_WIDTH: Float get() = GameConfig.PIPE_WIDTH
    }
}
