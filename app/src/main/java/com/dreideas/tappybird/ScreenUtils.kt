package com.dreideas.tappybird

import android.content.Context
import android.util.DisplayMetrics

/**
 * Three coordinate systems coexist in this game:
 *
 *   1. **World units (wu)** — the abstract space game logic runs in.
 *      Reference world is GameConfig.WORLD_WIDTH × GameConfig.WORLD_HEIGHT.
 *      GameView owns the world → screen transform (scale + offset).
 *
 *   2. **Density-independent pixels (dp)** — used for UI that must look the
 *      same physical size on every device regardless of pixel density
 *      (score digits, overlay text, touch targets). 1 dp = 1 px on a
 *      160-dpi "baseline" screen; on a 480-dpi screen 1 dp = 3 px.
 *
 *   3. **Physical pixels (px)** — what Canvas actually draws in. Everything
 *      must eventually resolve to px.
 *
 * This helper handles only the dp ⇄ px side; the world ⇄ screen transform
 * lives in GameView because it depends on runtime surface size.
 */
object ScreenUtils {

    /** Convert density-independent pixels → physical pixels on this device. */
    fun dpToPx(dp: Float, context: Context): Float =
        dp * context.resources.displayMetrics.density

    /** Same as [dpToPx] but avoids re-reading metrics on the hot path. */
    fun dpToPx(dp: Float, metrics: DisplayMetrics): Float =
        dp * metrics.density

    /** Convert physical pixels → density-independent pixels. */
    fun pxToDp(px: Float, context: Context): Float =
        px / context.resources.displayMetrics.density
}
