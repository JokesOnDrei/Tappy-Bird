package com.dreideas.tappybird.sprites

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Pixel-art bird sprite — the "asset" for the bird's appearance.
 *
 * The sprite is authored as a 2D grid of characters; each character maps to
 * a [Paint] via [PALETTE]. Rendering is a per-cell `drawRect` loop, so the
 * sprite stays crisp at any scale and the data is human-readable / editable.
 *
 * To change the bird's look, edit [GRID]. To add new colors, extend [PALETTE]
 * with a new character key. Cell '.' is transparent (skipped at draw time).
 *
 * Why this format and not a PNG / VectorDrawable:
 *   - The project is asset-free by design (procedural rendering).
 *   - PNGs require binary editing tools and clutter the resource pipeline.
 *   - VectorDrawables don't have a concise per-pixel format — a 17×12 bird
 *     would be ~80 path nodes of unreadable XML.
 *   - This grid is THE pixel art; you can read it like a sprite-editor view.
 */
object BirdSprite {

    /**
     * The sprite grid (17 cols × 12 rows).
     *
     * Color legend:
     *   B = dark outline       Y = yellow body       y = yellow shadow
     *   W = white              R = red wing          r = red highlight
     *   O = orange beak        o = orange shadow     . = transparent
     *
     * The bird faces RIGHT: beak on the right edge, eye in the upper-right,
     * white belly on the lower-left, red wing across the lower-middle.
     */
    private val GRID: Array<String> = arrayOf(
        "....BBBBBB.......",  // 0  head top
        "...BYYYYYWBB.....",  // 1
        "..BYYYYBBBWBB....",  // 2  eye top outline
        "..BYYYYBWWWBWB...",  // 3  eye + glint
        ".BYYYYYBWBWBBOOOB",  // 4  pupil + beak top (3 wide, blunt)
        ".BYYYYYBWWWBOOOOB",  // 5  beak (4 wide)
        ".BWWWYYYBBBBOOOOB",  // 6  beak (4 wide, max)
        "BWWBYYYYYYBBOOOB.",  // 7  belly + beak (3 wide, tapers)
        "BWBRRRRRRBYYBB...",  // 8  wing top
        ".BBRrrrrrRBYB....",  // 9  wing middle (highlight)
        ".BBRRRRRRRBB.....",  // 10 wing bottom
        "..BBBBBBBBB......",  // 11 body bottom
    )

    /** Sprite cell dimensions. */
    val widthCells: Int = GRID[0].length
    val heightCells: Int = GRID.size

    /**
     * Color → Paint mapping. Each Paint is allocated once at object-init
     * and reused across every render call — zero allocations on the
     * render hot path.
     */
    private val PALETTE: Map<Char, Paint> = mapOf(
        'B' to solid(Color.rgb(43, 28, 16)),     // outline
        'Y' to solid(Color.rgb(252, 220, 90)),   // bright yellow body
        'y' to solid(Color.rgb(210, 175, 55)),   // yellow shadow
        'W' to solid(Color.WHITE),
        'R' to solid(Color.rgb(220, 70, 60)),    // red wing
        'r' to solid(Color.rgb(250, 110, 95)),   // red wing highlight
        'O' to solid(Color.rgb(255, 165, 50)),   // orange beak
        'o' to solid(Color.rgb(230, 125, 30)),   // orange beak shadow
    )

    private fun solid(c: Int): Paint = Paint().apply {
        color = c
        // Anti-alias intentionally OFF: pixel-art edges should be crisp.
        // (Default for Paint() with no flags is AA off, but stating it.)
        isAntiAlias = false
        isDither = false
    }

    /**
     * Draw the sprite centered at (cx, cy) in world units, rotated by
     * [rotationDegrees] around that center. Each non-transparent cell
     * becomes a [pixelSize]×[pixelSize] square in world units.
     *
     * Caller is expected to be INSIDE the world transform (the
     * `canvas.translate + canvas.scale` block in GameView.render). All
     * coordinates here are world units.
     */
    fun render(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        pixelSize: Float,
        rotationDegrees: Float
    ) {
        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)
        val left = cx - widthCells * pixelSize / 2f
        val top = cy - heightCells * pixelSize / 2f
        for (row in 0 until heightCells) {
            val rowStr = GRID[row]
            val cellTop = top + row * pixelSize
            val cellBottom = cellTop + pixelSize
            for (col in 0 until widthCells) {
                val paint = PALETTE[rowStr[col]] ?: continue
                val cellLeft = left + col * pixelSize
                canvas.drawRect(cellLeft, cellTop, cellLeft + pixelSize, cellBottom, paint)
            }
        }
        canvas.restore()
    }
}
