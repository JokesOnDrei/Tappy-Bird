package com.dreideas.tappybird.entities

import com.dreideas.tappybird.GameConfig

/**
 * A top/bottom pipe pair sharing one X coordinate and a randomized
 * vertical gap center. The pair is the unit of scrolling and scoring.
 *
 * Ownership: [com.dreideas.tappybird.GameView] keeps a fixed-size pool of
 * these and recycles them as they leave the screen.
 */
class PipePair(
    var x: Float,
    var gapY: Float
) {
    val width: Float = GameConfig.PIPE_WIDTH
    val gapHeight: Float = GameConfig.GAP_HEIGHT

    /** Set to true once the bird's X passes our right edge; prevents double-scoring. */
    var scored: Boolean = false

    /** Scroll leftward at constant speed. */
    fun update(dt: Float) {
        x -= GameConfig.SCROLL_SPEED * dt
    }

    /** The pair has fully exited the left edge of the screen. */
    fun isOffScreenLeft(): Boolean = (x + width) < 0f

    /** Upper pipe bounds: from y=0 down to the top of the gap. */
    fun topPipeLeft(): Float = x
    fun topPipeRight(): Float = x + width
    fun topPipeTop(): Float = 0f
    fun topPipeBottom(): Float = gapY - gapHeight / 2f

    /** Lower pipe bounds: from the bottom of the gap down to the ground. */
    fun bottomPipeLeft(): Float = x
    fun bottomPipeRight(): Float = x + width
    fun bottomPipeTop(): Float = gapY + gapHeight / 2f
    /** Bottom is provided by the caller (screen height minus ground). */
    fun bottomPipeBottom(bottomY: Float): Float = bottomY

    /** Recycle this pair back to the right edge with a new gap center. */
    fun recycle(newX: Float, newGapY: Float) {
        x = newX
        gapY = newGapY
        scored = false
    }
}
