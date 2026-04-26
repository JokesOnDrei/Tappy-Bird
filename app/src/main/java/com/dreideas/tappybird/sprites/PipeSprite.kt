package com.dreideas.tappybird.sprites

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Pixel-art pipe sprite — the "asset" for pipe rendering.
 *
 * A pipe is a vertical column with a wider "lip" cap at the gap-facing end.
 * The look comes from a horizontal cross-section of vertical color bands:
 * dark outline → shadow → mid green → bright highlight → mid → highlight2
 * → mid → shadow → outline. Two highlight stripes give the classic
 * pixel-art "shine" you see in the reference image.
 *
 * The sprite is rendered procedurally (not as a fixed bitmap) because pipes
 * vary in height every frame as they scroll. Bands are defined as fractions
 * of the pipe's body width so PIPE_WIDTH can change in [com.dreideas.tappybird.GameConfig]
 * without breaking the pattern.
 *
 * To change the pipe's look, edit [BAND_FRACS] (color positions across the
 * cross-section) and / or the palette colors below.
 */
object PipeSprite {

    /** How tall the lip cap is, in world units. */
    private const val LIP_HEIGHT = 56f

    /** How far the lip overhangs the body width on each side, in world units. */
    private const val LIP_OVERHANG = 12f

    /** Thickness of the dark cap/divider lines around the lip, in world units. */
    private const val OUTLINE_THICKNESS = 5f

    // -- Palette --

    private val OUTLINE = solid(Color.rgb(50, 30, 15))      // dark brown ink
    private val SHADOW = solid(Color.rgb(50, 110, 40))      // dark green shadow band
    private val MID = solid(Color.rgb(90, 175, 75))         // main body green
    private val HIGHLIGHT = solid(Color.rgb(130, 205, 95))  // vertical highlight stripe
    private val BRIGHT = solid(Color.rgb(180, 230, 130))    // brightest core of highlight

    private fun solid(c: Int): Paint = Paint().apply {
        color = c
        isAntiAlias = false
        isDither = false
    }

    /**
     * Horizontal cross-section of the pipe body, as (startFraction, paint)
     * pairs. Each band fills `[start, nextStart)` of the pipe's width.
     * The last band implicitly runs to 1.0.
     *
     * Pattern (left → right):
     *   outline | shadow | mid | highlight | bright | highlight | mid |
     *   highlight | mid | shadow | outline
     */
    private val BAND_FRACS: List<Pair<Float, Paint>> = listOf(
        0.00f to OUTLINE,
        0.04f to SHADOW,
        0.10f to MID,
        0.22f to HIGHLIGHT,
        0.28f to BRIGHT,
        0.32f to HIGHLIGHT,
        0.40f to MID,
        0.62f to HIGHLIGHT,
        0.66f to MID,
        0.86f to SHADOW,
        0.96f to OUTLINE,
    )

    // =====================================================================
    //  Public render API — top vs. bottom pipe orientation
    // =====================================================================

    /**
     * Top pipe: a column descending from above with the lip at the BOTTOM
     * (gap-facing). Body fills [top, bottom-LIP_HEIGHT], lip below that.
     */
    fun renderTopPipe(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val lipTop = (bottom - LIP_HEIGHT).coerceAtLeast(top)
        // Body column (above the lip)
        if (lipTop > top) {
            renderColumnBands(canvas, left, top, right, lipTop)
        }
        // Lip — wider than body, with horizontal cap line at the bottom edge.
        val lipLeft = left - LIP_OVERHANG
        val lipRight = right + LIP_OVERHANG
        renderLip(canvas, lipLeft, lipTop, lipRight, bottom, capAtBottom = true)
    }

    /**
     * Bottom pipe: a column ascending from below with the lip at the TOP
     * (gap-facing). Lip first, then body fills [top+LIP_HEIGHT, bottom].
     */
    fun renderBottomPipe(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val lipBottom = (top + LIP_HEIGHT).coerceAtMost(bottom)
        // Lip — wider than body, with horizontal cap line at the top edge.
        val lipLeft = left - LIP_OVERHANG
        val lipRight = right + LIP_OVERHANG
        renderLip(canvas, lipLeft, top, lipRight, lipBottom, capAtBottom = false)
        // Body column (below the lip)
        if (lipBottom < bottom) {
            renderColumnBands(canvas, left, lipBottom, right, bottom)
        }
    }

    // =====================================================================
    //  Internals — band rendering
    // =====================================================================

    /** Fill the rect with vertical bands per [BAND_FRACS]. */
    private fun renderColumnBands(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val w = right - left
        for (i in BAND_FRACS.indices) {
            val (startFrac, paint) = BAND_FRACS[i]
            val endFrac = if (i + 1 < BAND_FRACS.size) BAND_FRACS[i + 1].first else 1f
            canvas.drawRect(left + w * startFrac, top, left + w * endFrac, bottom, paint)
        }
    }

    /**
     * Lip: same vertical banding pattern as the body, plus a dark cap line
     * at the outward-facing edge and a divider line at the inward edge
     * (where the lip meets the body column).
     */
    private fun renderLip(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        capAtBottom: Boolean
    ) {
        renderColumnBands(canvas, left, top, right, bottom)
        // Outermost cap line (the gap-facing rim)
        if (capAtBottom) {
            canvas.drawRect(left, bottom - OUTLINE_THICKNESS, right, bottom, OUTLINE)
            // Inner divider — visually separates the lip from the body column
            canvas.drawRect(left, top, right, top + OUTLINE_THICKNESS, OUTLINE)
        } else {
            canvas.drawRect(left, top, right, top + OUTLINE_THICKNESS, OUTLINE)
            canvas.drawRect(left, bottom - OUTLINE_THICKNESS, right, bottom, OUTLINE)
        }
    }
}
