package com.dreideas.tappybird.sprites

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dreideas.tappybird.GameConfig

/**
 * Pixel-art rank icons shown in the Game Over scoreboard.
 *
 * Five ranks, mapped from the player's final score (see [forScore]):
 *   - **ROOKIE** (0–9)    — white egg / "just hatched"
 *   - **CASUAL** (10–29)  — yellow wing (the bird's own wings)
 *   - **DECENT** (30–49)  — yellow thumbs-up
 *   - **ACE**    (50–99)  — gold star
 *   - **LEGEND** (100+)   — gold crown with red gems
 *
 * Visual progression climbs in "weight": plain white → warm yellow → gold
 * → royal gold-with-gems. Hierarchy is legible at a glance even before the
 * rank name is read.
 *
 * Each icon is a 12×12 char grid using the same authoring pattern as
 * [BirdSprite] / [PipeSprite] — paints allocated once, no per-frame GC,
 * crisp edges (anti-alias off).
 */
object RankSprite {

    enum class Rank { ROOKIE, CASUAL, DECENT, ACE, LEGEND }

    /** Map a final score to a rank tier. */
    fun forScore(score: Int): Rank = when {
        score >= GameConfig.RANK_LEGEND_THRESHOLD -> Rank.LEGEND
        score >= GameConfig.RANK_ACE_THRESHOLD -> Rank.ACE
        score >= GameConfig.RANK_DECENT_THRESHOLD -> Rank.DECENT
        score >= GameConfig.RANK_CASUAL_THRESHOLD -> Rank.CASUAL
        else -> Rank.ROOKIE
    }

    /** Display label, shown beneath the icon in the scoreboard panel. */
    fun displayName(rank: Rank): String = when (rank) {
        Rank.ROOKIE -> "ROOKIE"
        Rank.CASUAL -> "CASUAL"
        Rank.DECENT -> "DECENT"
        Rank.ACE -> "ACE"
        Rank.LEGEND -> "LEGEND"
    }

    val widthCells: Int = 12
    val heightCells: Int = 12

    // ---------------------------------------------------------------------
    //  Sprite grids — 12×12 each. Edit these to tweak the icons.
    // ---------------------------------------------------------------------

    /** ROOKIE — clean white egg. Plain, humble starter icon. */
    private val GRID_ROOKIE: Array<String> = arrayOf(
        "............",
        "....BBBB....",
        "...BWWWWB...",
        "..BWWWWWWB..",
        ".BWWWWWWWWB.",
        ".BWWWWWWWWB.",
        ".BWWWWWWWWB.",
        ".BWWWWWWWWB.",
        ".BWWWWWWWWB.",
        "..BWWWWWWB..",
        "...BWWWWB...",
        "....BBBB....",
    )

    /** CASUAL — yellow wing with diagonal feather-vane lines (matches the bird). */
    private val GRID_CASUAL: Array<String> = arrayOf(
        "............",
        ".....BBB....",
        "....BYYYB...",
        "...BYYYYB...",
        "..BYYYYYB...",
        "..BYBYYYB...",
        "..BYYBYYB...",
        "..BYYYBYB...",
        "..BYYYYYB...",
        "...BBBBB....",
        "............",
        "............",
    )

    /** DECENT — yellow thumbs-up. "Decent job, kid." */
    private val GRID_DECENT: Array<String> = arrayOf(
        "............",
        "............",
        "....BB......",
        "...BYYB.....",
        "...BYYB.....",
        "...BYYBBB...",
        "..BYYYYYB...",
        "..BYYYYYB...",
        "..BYYYYYB...",
        "..BYYYYYB...",
        "..BBBBBBB...",
        "............",
    )

    /** ACE — chunky 5-pointed gold star. Top-tier-but-not-royalty. */
    private val GRID_ACE: Array<String> = arrayOf(
        "............",
        "............",
        ".....BB.....",
        "....BYYB....",
        "....BYYB....",
        "...BYYYYB...",
        "BBBBYYYYBBBB",
        ".BYYYYYYYYB.",
        "..BYYYYYYB..",
        "..BYBBBBYB..",
        ".BYB....BYB.",
        "BB........BB",
    )

    /** LEGEND — gold crown with three red gems. Royal top tier. */
    private val GRID_LEGEND: Array<String> = arrayOf(
        "............",
        "............",
        "..BB.BB.BB..",
        "..BB.BB.BB..",
        ".BBBBBBBBBB.",
        ".BYYYYYYYYB.",
        ".BYRYYRYYRB.",
        ".BYYYYYYYYB.",
        ".BBBBBBBBBB.",
        "............",
        "............",
        "............",
    )

    // ---------------------------------------------------------------------
    //  Palette — paints allocated once, mutated zero times per render.
    // ---------------------------------------------------------------------

    private val PALETTE: Map<Char, Paint> = mapOf(
        'B' to solid(Color.rgb(43, 28, 16)),     // dark outline
        'Y' to solid(Color.rgb(252, 220, 90)),   // yellow / gold
        'W' to solid(Color.WHITE),               // white (egg)
        'R' to solid(Color.rgb(220, 70, 60)),    // red (crown gems)
    )

    private fun solid(c: Int): Paint = Paint().apply {
        color = c
        isAntiAlias = false
        isDither = false
    }

    private fun gridFor(rank: Rank): Array<String> = when (rank) {
        Rank.ROOKIE -> GRID_ROOKIE
        Rank.CASUAL -> GRID_CASUAL
        Rank.DECENT -> GRID_DECENT
        Rank.ACE -> GRID_ACE
        Rank.LEGEND -> GRID_LEGEND
    }

    // ---------------------------------------------------------------------
    //  Rendering
    // ---------------------------------------------------------------------

    /**
     * Draw the [rank]'s icon centered at (cx, cy). Each cell becomes a
     * `pixelSize × pixelSize` square in the same units as the caller's
     * canvas (use pixels here — the scoreboard panel renders in pixels).
     */
    fun render(canvas: Canvas, rank: Rank, cx: Float, cy: Float, pixelSize: Float) {
        val grid = gridFor(rank)
        val left = cx - widthCells * pixelSize / 2f
        val top = cy - heightCells * pixelSize / 2f
        for (row in 0 until heightCells) {
            val rowStr = grid[row]
            val cellTop = top + row * pixelSize
            val cellBottom = cellTop + pixelSize
            for (col in 0 until widthCells) {
                val paint = PALETTE[rowStr[col]] ?: continue
                val cellLeft = left + col * pixelSize
                canvas.drawRect(cellLeft, cellTop, cellLeft + pixelSize, cellBottom, paint)
            }
        }
    }
}
