package com.dreideas.tappybird

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dreideas.tappybird.audio.SoundManager
import com.dreideas.tappybird.entities.Bird
import com.dreideas.tappybird.entities.PipePair
import com.dreideas.tappybird.sprites.BirdSprite
import com.dreideas.tappybird.sprites.PipeSprite
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The game's SurfaceView. Responsibilities:
 *   - world state (bird, pipe pool, score, game state) in **world units**
 *   - render thread (fixed-timestep update + decoupled render)
 *   - world → screen transform (scale + offset) recomputed per surface size
 *   - safe-area inset tracking for notches / gesture bars
 *   - touch input
 *
 * Coordinate-system summary
 * -------------------------
 *   - Gameplay runs in **world units** on a virtual canvas of
 *     [effectiveWorldWidth] × [effectiveWorldHeight] — ≥ the reference
 *     1080 × 1920, extended (not letterboxed) on taller/wider devices.
 *   - [render] pushes a single `canvas.translate(offset) + canvas.scale(scale)`
 *     transform so sub-drawing code writes in world units naturally.
 *   - **UI text** (score, banners) is drawn AFTER popping that transform,
 *     using dp-based text sizes so it keeps a consistent physical size.
 *
 * Rendering is done with primitive Canvas shapes (no external assets).
 * Swapping sprites in later: draw bitmaps at their world-unit size inside
 * the transformed section (the scale matrix handles DPI for free), and set
 * `paint.isFilterBitmap = true` for bilinear filtering.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    // ---------------------------------------------------------------------
    //  Threading
    // ---------------------------------------------------------------------

    private var gameThread: GameThread? = null

    // ---------------------------------------------------------------------
    //  World state (guarded by @Synchronized on update/render/input)
    // ---------------------------------------------------------------------

    private val highScoreRepo = HighScoreRepository(context)
    private val sound = SoundManager(context)

    private var state: GameState = GameState.Ready
    private var score: Int = 0
    private var highScore: Int = highScoreRepo.getHighScore()
    private var isNewHighScore: Boolean = false

    /**
     * If true, the bird died from a pipe hit and is currently free-falling.
     * When it touches the ground in [updateGameOver] we play the soft thud
     * to bookend the hit cue. False if the bird hit the ground directly
     * (no follow-up thud — the fall sound was already played at GameOver entry).
     */
    private var deathThudPending: Boolean = false

    /** What killed the bird this frame. Drives which audio cue plays. */
    private enum class HitKind { NONE, GROUND, PIPE }

    /** Medal earned at game-over. NONE if the score didn't clear bronze. */
    private enum class Medal { NONE, BRONZE, SILVER, GOLD }

    private fun medalForScore(s: Int): Medal = when {
        s >= GameConfig.MEDAL_GOLD_THRESHOLD -> Medal.GOLD
        s >= GameConfig.MEDAL_SILVER_THRESHOLD -> Medal.SILVER
        s >= GameConfig.MEDAL_BRONZE_THRESHOLD -> Medal.BRONZE
        else -> Medal.NONE
    }

    // ---------------------------------------------------------------------
    //  Device surface (in physical pixels)
    // ---------------------------------------------------------------------

    private var screenW: Float = 0f
    private var screenH: Float = 0f

    // ---------------------------------------------------------------------
    //  World & transform (world units + pixel offsets)
    // ---------------------------------------------------------------------

    /** Effective playable world width (wu). ≥ WORLD_WIDTH; grows on wide screens. */
    private var worldW: Float = GameConfig.WORLD_WIDTH

    /** Effective playable world height (wu). ≥ WORLD_HEIGHT; grows on tall screens. */
    private var worldH: Float = GameConfig.WORLD_HEIGHT

    /** Uniform world → screen scale factor (px per world unit). */
    private var scale: Float = 1f

    /** Pixel offset of world origin (0,0) on screen. Non-zero only on ultra-short screens. */
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    /** World-unit Y of the top edge of the ground strip. */
    private var groundTopY: Float = GameConfig.WORLD_HEIGHT - GameConfig.GROUND_HEIGHT

    // ---------------------------------------------------------------------
    //  Safe-area insets (in physical pixels) — set via WindowInsets callback
    // ---------------------------------------------------------------------

    private var safeAreaTop: Float = 0f
    private var safeAreaBottom: Float = 0f
    private var safeAreaLeft: Float = 0f
    private var safeAreaRight: Float = 0f

    // ---------------------------------------------------------------------
    //  Entities
    // ---------------------------------------------------------------------

    private lateinit var bird: Bird
    private val pipePairs: MutableList<PipePair> = ArrayList(GameConfig.PIPE_POOL_SIZE)

    // ---------------------------------------------------------------------
    //  Timing
    // ---------------------------------------------------------------------

    private var readyElapsed: Float = 0f
    private var gameOverElapsed: Float = 0f
    private var totalElapsed: Float = 0f
    private var groundOffset: Float = 0f

    private val rng = Random(System.nanoTime())

    // ---------------------------------------------------------------------
    //  Density & paints
    //
    //  WORLD paints: strokeWidth / textSize in WORLD UNITS; used inside the
    //  scaled canvas block, so values scale with the device automatically.
    //
    //  UI paints: strokeWidth / textSize in PIXELS (dp→px at init); used
    //  outside the scaled block so physical size is DPI-consistent.
    // ---------------------------------------------------------------------

    private val density: Float = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    /**
     * Pixel-style fonts loaded from `res/font/`. Resolved by name at
     * runtime via `getIdentifier` so a missing file is a logged warning
     * rather than a compile error — same graceful-degrade pattern as audio.
     *
     * Two weights are used to give a clean visual hierarchy:
     *   - [pixelFontTitle] — bold, for titles + numbers
     *   - [pixelFontBody]  — medium, for labels
     */
    private val pixelFontTitle: Typeface =
        loadFontRes(GameConfig.PIXEL_FONT_TITLE_RES, Typeface.DEFAULT_BOLD)
    private val pixelFontBody: Typeface =
        loadFontRes(GameConfig.PIXEL_FONT_BODY_RES, Typeface.DEFAULT)

    private fun loadFontRes(name: String, fallback: Typeface): Typeface {
        @Suppress("DiscouragedApi")  // intentional: lazy resolution lets missing fonts degrade gracefully
        val id = context.resources.getIdentifier(name, "font", context.packageName)
        if (id == 0) {
            android.util.Log.i(
                "TappyBird/Font",
                "Font '$name' not in res/font — falling back to system default."
            )
            return fallback
        }
        return try {
            ResourcesCompat.getFont(context, id) ?: fallback
        } catch (_: android.content.res.Resources.NotFoundException) {
            fallback
        }
    }

    // World paints
    private val skyPaint = Paint().apply { color = Color.rgb(112, 197, 206) }
    // Pipes are rendered via PipeSprite — no inline pipe paints needed here.
    private val groundPaint = Paint().apply { color = Color.rgb(221, 216, 148) }
    private val groundStripeAPaint = Paint().apply { color = Color.rgb(206, 199, 130) }
    private val groundTopPaint = Paint().apply { color = Color.rgb(90, 175, 73) }
    // Bird is rendered via BirdSprite — no per-feature paints needed here.

    // ---------- UI paints (dp-sized; pixel-font typeface throughout) ----------

    // Big in-game score (white digits, dark stroke) — title/bold weight
    private val scorePaint = pixelTextPaint(dp(72f), Color.WHITE, align = Paint.Align.CENTER)
    private val scoreStrokePaint = pixelTextStrokePaint(dp(72f), Color.rgb(60, 35, 15), dp(6f), align = Paint.Align.CENTER)

    // Title — "TAPPY BIRD" / "GAME OVER" — chunky orange with dark outline
    private val titlePaint = pixelTextPaint(dp(56f), Color.rgb(252, 152, 30), align = Paint.Align.CENTER)
    private val titleStrokePaint = pixelTextStrokePaint(dp(56f), Color.rgb(95, 50, 15), dp(8f), align = Paint.Align.CENTER)

    // Body labels — "TAP TO START" / "TAP TO RESTART" — medium weight
    private val labelPaint = pixelTextPaint(dp(30f), Color.WHITE, align = Paint.Align.CENTER, font = pixelFontBody)
    private val labelStrokePaint = pixelTextStrokePaint(dp(30f), Color.rgb(60, 35, 15), dp(4f), align = Paint.Align.CENTER, font = pixelFontBody)

    // Small label — "HIGH SCORE: 90" on the Ready screen — medium weight
    private val smallLabelPaint = pixelTextPaint(dp(20f), Color.WHITE, align = Paint.Align.CENTER, font = pixelFontBody)
    private val smallLabelStrokePaint = pixelTextStrokePaint(dp(20f), Color.rgb(60, 35, 15), dp(3f), align = Paint.Align.CENTER, font = pixelFontBody)

    /**
     * Game-over scoreboard panel as a layer-list drawable
     * ([R.drawable.scoreboard_panel]). Loaded once; bounds are set per-frame
     * before drawing. Falls back to procedural rectangles if (somehow) the
     * resource doesn't load.
     */
    private val scoreboardDrawable: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.scoreboard_panel)

    // Procedural fallback paints — used only if the drawable fails to load.
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 35, 15) }
    private val panelFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 220, 165) }
    private val panelInnerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 235, 195) }

    // Panel labels — "MEDAL" / "SCORE" / "HIGH SCORE" — medium weight
    private val panelLabelCenterPaint = pixelTextPaint(dp(16f), Color.rgb(110, 145, 95), align = Paint.Align.CENTER, font = pixelFontBody)
    private val panelLabelRightPaint = pixelTextPaint(dp(16f), Color.rgb(110, 145, 95), align = Paint.Align.RIGHT, font = pixelFontBody)

    // Panel score numbers (right-aligned, white with dark stroke)
    private val panelNumberPaint = pixelTextPaint(dp(28f), Color.WHITE, align = Paint.Align.RIGHT)
    private val panelNumberStrokePaint = pixelTextStrokePaint(dp(28f), Color.rgb(60, 35, 15), dp(4f), align = Paint.Align.RIGHT)

    // Medal palette
    private val medalRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 185, 130) }
    private val medalEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 225, 175) }
    private val medalBronzePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(195, 110, 50) }
    private val medalSilverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(195, 200, 215) }
    private val medalGoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 200, 60) }
    private val medalShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }

    // "NEW HIGH SCORE!" banner (gold pixel text)
    private val newHighScorePaint = pixelTextPaint(dp(24f), Color.rgb(250, 205, 40), align = Paint.Align.CENTER)
    private val newHighScoreStrokePaint = pixelTextStrokePaint(dp(24f), Color.rgb(60, 35, 15), dp(3f), align = Paint.Align.CENTER)

    // Pixel-text paint factories — used only at init.
    private fun pixelTextPaint(
        size: Float,
        fillColor: Int,
        align: Paint.Align,
        font: Typeface = pixelFontTitle
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            textAlign = align
            textSize = size
            typeface = font
        }

    private fun pixelTextStrokePaint(
        size: Float,
        strokeColor: Int,
        width: Float,
        align: Paint.Align,
        font: Typeface = pixelFontTitle
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            textAlign = align
            textSize = size
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeJoin = Paint.Join.ROUND
            typeface = font
        }

    private val tmpRect = RectF()

    init {
        holder.addCallback(this)
        isFocusable = true
        setupInsets()
    }

    // =====================================================================
    //  Safe-area insets
    // =====================================================================

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            safeAreaTop = max(bars.top, cutout.top).toFloat()
            safeAreaBottom = max(bars.bottom, cutout.bottom).toFloat()
            safeAreaLeft = max(bars.left, cutout.left).toFloat()
            safeAreaRight = max(bars.right, cutout.right).toFloat()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Force an inset pass now that we're in the hierarchy.
        ViewCompat.requestApplyInsets(this)
    }

    // =====================================================================
    //  SurfaceHolder.Callback — thread lifecycle
    // =====================================================================

    override fun surfaceCreated(holder: SurfaceHolder) {
        startGameThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        synchronized(this) {
            screenW = w.toFloat()
            screenH = h.toFloat()
            recomputeScaling()
            if (!::bird.isInitialized) {
                initWorld()
            } else {
                // Reposition bird horizontally relative to the new effective world.
                bird.x = worldW * GameConfig.BIRD_X_FRACTION
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopGameThread()
    }

    /**
     * Compute the world → screen transform for the current surface size.
     *
     * Strategy: uniform scale (never stretches the bird); extend the world
     * dimension that would otherwise be letterboxed, so there are no black
     * bars on tall or wide devices.
     */
    fun recomputeScaling() {
        if (screenW <= 0f || screenH <= 0f) return

        val scaleX = screenW / GameConfig.WORLD_WIDTH
        val scaleY = screenH / GameConfig.WORLD_HEIGHT

        if (scaleX < scaleY) {
            // Screen is TALLER than 9:16 (modern phones, 19.5:9 / 20:9 / 21:9).
            // Width-limited scale → extend world vertically to fill the extra
            // vertical space instead of letterboxing with top/bottom bars.
            scale = scaleX
            worldW = GameConfig.WORLD_WIDTH
            worldH = screenH / scale
            offsetX = 0f
            offsetY = 0f
        } else {
            // Screen is WIDER than 9:16 (tablets in portrait, foldables).
            // Height-limited scale → extend world horizontally.
            scale = scaleY
            worldH = GameConfig.WORLD_HEIGHT
            worldW = screenW / scale
            offsetX = 0f
            offsetY = 0f
        }

        groundTopY = worldH - GameConfig.GROUND_HEIGHT
    }

    // =====================================================================
    //  Public lifecycle (called by host Activity)
    // =====================================================================

    fun pause() {
        stopGameThread()
        sound.pauseMusic()
    }

    fun resume() {
        if (holder.surface?.isValid == true) startGameThread()
        sound.resumeMusic()
    }

    /**
     * Terminal teardown — call from `Activity.onDestroy`. After this the
     * audio resources are gone and the view should not be reused.
     */
    fun releaseResources() {
        sound.release()
    }

    // =====================================================================
    //  World (re)initialization — all coordinates in world units
    // =====================================================================

    private fun initWorld() {
        bird = Bird(
            x = worldW * GameConfig.BIRD_X_FRACTION,
            y = worldH * 0.5f
        )
        pipePairs.clear()
        // Consecutive gap Ys are clamped so the vertical traversal between
        // pipes never exceeds MAX_GAP_DELTA (see randomGapY).
        val firstX = worldW + GameConfig.PIPE_SPACING
        var prevGapY: Float? = null
        repeat(GameConfig.PIPE_POOL_SIZE) { i ->
            val gapY = randomGapY(prevGapY)
            pipePairs.add(
                PipePair(
                    x = firstX + i * GameConfig.PIPE_SPACING,
                    gapY = gapY
                )
            )
            prevGapY = gapY
        }
        readyElapsed = 0f
        gameOverElapsed = 0f
        totalElapsed = 0f
        groundOffset = 0f
        score = 0
        isNewHighScore = false
        deathThudPending = false
        state = GameState.Ready
        // Spec: music plays through Ready and Playing. startMusic is idempotent
        // and resets ducked volume back to MUSIC_VOLUME, so this covers both
        // first launch and post-GameOver restart.
        sound.startMusic()
    }

    /**
     * @param prevGapY Previous pipe pair's gap center (world units), or
     *   null for the first pipe. When supplied, result is clamped to
     *   prevGapY ± MAX_GAP_DELTA so every transition is physically reachable.
     */
    private fun randomGapY(prevGapY: Float? = null): Float {
        val halfGap = GameConfig.GAP_HEIGHT / 2f
        var minY = GameConfig.PIPE_GAP_MARGIN + halfGap
        var maxY = groundTopY - GameConfig.PIPE_GAP_MARGIN - halfGap
        if (prevGapY != null) {
            minY = max(minY, prevGapY - GameConfig.MAX_GAP_DELTA)
            maxY = min(maxY, prevGapY + GameConfig.MAX_GAP_DELTA)
        }
        if (maxY <= minY) return groundTopY * 0.5f
        return minY + rng.nextFloat() * (maxY - minY)
    }

    // =====================================================================
    //  Update — called at fixed dt by GameThread (all in world units)
    // =====================================================================

    @Synchronized
    fun update(dt: Float) {
        if (screenW <= 0f || screenH <= 0f || !::bird.isInitialized) return

        totalElapsed += dt
        when (state) {
            GameState.Ready -> updateReady(dt)
            GameState.Playing -> updatePlaying(dt)
            GameState.GameOver -> updateGameOver(dt)
        }
    }

    private fun updateReady(dt: Float) {
        readyElapsed += dt
        bird.y = worldH * 0.5f +
            sin(readyElapsed * GameConfig.BIRD_BOB_FREQUENCY) *
            GameConfig.BIRD_BOB_AMPLITUDE
        bird.velocityY = 0f
        bird.rotationDegrees = cos(readyElapsed * GameConfig.BIRD_BOB_FREQUENCY) * 5f
        advanceGround(dt)
    }

    private fun updatePlaying(dt: Float) {
        bird.update(dt)

        // Ceiling clamp: allow soft overshoot rather than instant death.
        // Uses the visible-silhouette half-height so the bird's top edge is
        // what stops at the ceiling (not the larger sprite-circle radius).
        if (bird.y - GameConfig.BIRD_COLLISION_HALF_HEIGHT < 0f) {
            bird.y = GameConfig.BIRD_COLLISION_HALF_HEIGHT
            if (bird.velocityY < 0f) bird.velocityY = 0f
        }

        advanceGround(dt)

        for (pair in pipePairs) {
            pair.update(dt)
            if (!pair.scored && bird.x > pair.x + pair.width) {
                pair.scored = true
                score++
                sound.playScore()
            }
            if (pair.isOffScreenLeft()) {
                val rightmost = pipePairs.maxByOrNull { it.x }!!
                pair.recycle(
                    newX = rightmost.x + GameConfig.PIPE_SPACING,
                    newGapY = randomGapY(rightmost.gapY)
                )
            }
        }

        val hit = checkCollision()
        if (hit != HitKind.NONE) enterGameOver(hit)
    }

    private fun updateGameOver(dt: Float) {
        gameOverElapsed += dt
        // Bird settles when its visible bottom edge reaches the ground.
        val restingY = groundTopY - GameConfig.BIRD_COLLISION_HALF_HEIGHT
        if (bird.y < restingY) {
            bird.update(dt)
            bird.rotationDegrees = min(bird.rotationDegrees + 540f * dt, 90f)
            if (bird.y >= restingY) {
                bird.y = restingY
                bird.velocityY = 0f
                if (deathThudPending) {
                    sound.playFall()
                    deathThudPending = false
                }
            }
        } else {
            bird.y = restingY
            bird.velocityY = 0f
        }
    }

    private fun advanceGround(dt: Float) {
        val stripe = GameConfig.GROUND_STRIPE_WIDTH
        groundOffset = (groundOffset - GameConfig.SCROLL_SPEED * dt) % stripe
        if (groundOffset > 0f) groundOffset -= stripe
    }

    // ---------------------------------------------------------------------
    //  Collisions (world units)
    // ---------------------------------------------------------------------

    private fun checkCollision(): HitKind {
        // Bird's visible silhouette as an AABB (matches the sprite extents).
        val bLeft = bird.x - GameConfig.BIRD_COLLISION_HALF_WIDTH
        val bRight = bird.x + GameConfig.BIRD_COLLISION_HALF_WIDTH
        val bTop = bird.y - GameConfig.BIRD_COLLISION_HALF_HEIGHT
        val bBottom = bird.y + GameConfig.BIRD_COLLISION_HALF_HEIGHT

        if (bBottom >= groundTopY) return HitKind.GROUND

        for (pair in pipePairs) {
            if (pair.x + pair.width < bLeft) continue
            if (pair.x > bRight) continue
            if (rectsOverlap(
                    bLeft, bTop, bRight, bBottom,
                    pair.topPipeLeft(), pair.topPipeTop(),
                    pair.topPipeRight(), pair.topPipeBottom()
                )
            ) return HitKind.PIPE
            if (rectsOverlap(
                    bLeft, bTop, bRight, bBottom,
                    pair.bottomPipeLeft(), pair.bottomPipeTop(),
                    pair.bottomPipeRight(), pair.bottomPipeBottom(groundTopY)
                )
            ) return HitKind.PIPE
        }
        return HitKind.NONE
    }

    /** Standard AABB overlap test. */
    private fun rectsOverlap(
        l1: Float, t1: Float, r1: Float, b1: Float,
        l2: Float, t2: Float, r2: Float, b2: Float
    ): Boolean = r1 > l2 && l1 < r2 && b1 > t2 && t1 < b2

    private fun enterGameOver(hit: HitKind) {
        state = GameState.GameOver
        gameOverElapsed = 0f
        when (hit) {
            HitKind.PIPE -> {
                sound.playHit()
                deathThudPending = true   // ground impact will play the follow-up thud
            }
            HitKind.GROUND -> {
                sound.playFall()
                deathThudPending = false  // already on the ground; no follow-up
            }
            HitKind.NONE -> Unit          // unreachable, defensive
        }
        sound.duckMusic()
        if (highScoreRepo.trySetHighScore(score)) {
            highScore = score
            isNewHighScore = true
        }
    }

    // =====================================================================
    //  Render
    // =====================================================================

    @Synchronized
    fun render(canvas: Canvas) {
        // Background fills the full PHYSICAL surface so the extra area on
        // wide/tall devices gets the sky color (never a black bar).
        canvas.drawRect(0f, 0f, screenW, screenH, skyPaint)

        if (!::bird.isInitialized) return

        // World layer — after this transform, everything draws in world units.
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        drawPipes(canvas)
        drawGround(canvas)
        drawBird(canvas)

        canvas.restore()

        // UI layer — physical pixels, dp-sized text, offset by safe-area insets.
        when (state) {
            GameState.Ready -> drawReadyOverlay(canvas)
            GameState.Playing -> drawScoreBig(canvas)
            GameState.GameOver -> drawGameOverOverlay(canvas)
        }
    }

    // ---- World-space drawing (called inside the scaled canvas) ----

    private fun drawPipes(canvas: Canvas) {
        for (pair in pipePairs) {
            // Top pipe — column descends from y=0 to gap, lip at the bottom.
            PipeSprite.renderTopPipe(
                canvas,
                pair.topPipeLeft(), pair.topPipeTop(),
                pair.topPipeRight(), pair.topPipeBottom()
            )
            // Bottom pipe — column ascends from gap to ground, lip at the top.
            PipeSprite.renderBottomPipe(
                canvas,
                pair.bottomPipeLeft(), pair.bottomPipeTop(),
                pair.bottomPipeRight(), pair.bottomPipeBottom(groundTopY)
            )
        }
    }

    private fun drawGround(canvas: Canvas) {
        // Draw across the FULL effective world width so wide screens have no
        // exposed sky strip at the edges.
        canvas.drawRect(0f, groundTopY, worldW, groundTopY + 12f, groundTopPaint)
        canvas.drawRect(0f, groundTopY + 12f, worldW, worldH, groundPaint)
        val stripe = GameConfig.GROUND_STRIPE_WIDTH
        var x = groundOffset
        while (x < worldW) {
            canvas.drawRect(
                x, groundTopY + 12f,
                x + stripe / 2f, worldH,
                groundStripeAPaint
            )
            x += stripe
        }
    }

    private fun drawBird(canvas: Canvas) {
        // Sprite spans the bird's diameter horizontally (visual scale only).
        // Collision uses an AABB matching the visible silhouette — see
        // BIRD_COLLISION_HALF_WIDTH / HEIGHT in GameConfig and checkCollision.
        val pixelSize = (bird.radius * 2f) / BirdSprite.widthCells
        BirdSprite.render(canvas, bird.x, bird.y, pixelSize, bird.rotationDegrees)
    }

    // ---- UI drawing (physical pixels, dp text, safe-area aware) ----

    /** Safe top edge in physical pixels — below notches & status bar. */
    private fun safeTop(): Float = safeAreaTop + dp(16f)

    /** Safe bottom edge in physical pixels — above gesture bar. */
    private fun safeBottom(): Float = screenH - safeAreaBottom - dp(16f)

    private fun drawScoreBig(canvas: Canvas) {
        val cx = screenW / 2f
        val y = safeTop() + dp(72f)
        val text = score.toString()
        canvas.drawText(text, cx, y, scoreStrokePaint)
        canvas.drawText(text, cx, y, scorePaint)
    }

    private fun drawReadyOverlay(canvas: Canvas) {
        val cx = screenW / 2f

        // Title at ~22% of screen, but never above the notch.
        val titleY = max(safeTop() + dp(72f), screenH * 0.22f)
        canvas.drawText("TAPPY BIRD", cx, titleY, titleStrokePaint)
        canvas.drawText("TAPPY BIRD", cx, titleY, titlePaint)

        // "Tap to start" + high score near the bottom, above gesture bar.
        val tapY = min(safeBottom() - dp(60f), screenH * 0.78f)
        canvas.drawText("TAP TO START", cx, tapY, labelStrokePaint)
        canvas.drawText("TAP TO START", cx, tapY, labelPaint)

        val hsY = tapY + dp(40f)
        val hsText = "HIGH SCORE: $highScore"
        canvas.drawText(hsText, cx, hsY, smallLabelStrokePaint)
        canvas.drawText(hsText, cx, hsY, smallLabelPaint)
    }

    private fun drawGameOverOverlay(canvas: Canvas) {
        val cx = screenW / 2f

        // ---- Layout: vertically center the (title + panel) group ----
        // Title and panel form a visual unit; we center that unit on the
        // screen, biased slightly upward so "NEW HIGH SCORE!" + "TAP TO
        // RESTART" still have room beneath without crowding the gesture bar.
        val panelW = min(dp(360f), screenW * 0.88f)
        val panelH = panelW * 0.50f                       // ~2:1 aspect, matches reference
        val titleSize = dp(56f)                           // matches titlePaint textSize
        val titleToPanelGap = dp(40f)
        val groupH = titleSize + titleToPanelGap + panelH
        val biasUp = dp(40f)                              // leave room below for restart prompt
        val groupTop = (screenH - groupH) / 2f - biasUp

        // Title baseline — clamp under safe top so notches never clip it.
        val headerY = max(safeTop() + dp(60f), groupTop + titleSize)
        // Panel sits gap-distance below the title baseline.
        val panelTop = headerY + titleToPanelGap
        val panelLeft = cx - panelW / 2f
        val panelRight = panelLeft + panelW
        val panelBottom = panelTop + panelH

        // ---- "GAME OVER" title (orange pixel block, dark stroke) ----
        canvas.drawText("GAME OVER", cx, headerY, titleStrokePaint)
        canvas.drawText("GAME OVER", cx, headerY, titlePaint)

        // ---- Scoreboard panel ----
        val drawable = scoreboardDrawable
        if (drawable != null) {
            // Pixel-asset path: render the layer-list drawable.
            drawable.setBounds(
                panelLeft.toInt(), panelTop.toInt(),
                panelRight.toInt(), panelBottom.toInt()
            )
            drawable.draw(canvas)
        } else {
            // Procedural fallback (shouldn't happen — kept for resilience).
            val cornerOuter = dp(14f)
            val cornerInner = dp(11f)
            val borderW = dp(4f)
            tmpRect.set(panelLeft, panelTop, panelRight, panelBottom)
            canvas.drawRoundRect(tmpRect, cornerOuter, cornerOuter, panelBorderPaint)
            tmpRect.set(panelLeft + dp(2f), panelTop + dp(2f), panelRight - dp(2f), panelBottom - dp(2f))
            canvas.drawRoundRect(tmpRect, dp(13f), dp(13f), panelInnerHighlightPaint)
            tmpRect.set(panelLeft + borderW, panelTop + borderW, panelRight - borderW, panelBottom - borderW)
            canvas.drawRoundRect(tmpRect, cornerInner, cornerInner, panelFillPaint)
        }

        // Layout zones inside the panel
        val medalCx = panelLeft + panelW * 0.27f
        val rightX = panelRight - dp(20f)
        val labelTop = panelTop + dp(28f)

        // ---- Left: MEDAL label + medal slot ----
        canvas.drawText("MEDAL", medalCx, labelTop, panelLabelCenterPaint)
        val medalCy = panelTop + panelH * 0.62f
        val medalR = panelH * 0.30f
        drawMedal(canvas, medalCx, medalCy, medalR, medalForScore(score))

        // ---- Right: SCORE + HIGH SCORE columns (right-aligned) ----
        canvas.drawText("SCORE", rightX, labelTop, panelLabelRightPaint)
        val scoreNumberY = labelTop + dp(28f)
        canvas.drawText(score.toString(), rightX, scoreNumberY, panelNumberStrokePaint)
        canvas.drawText(score.toString(), rightX, scoreNumberY, panelNumberPaint)

        val highLabelY = panelTop + panelH * 0.58f
        canvas.drawText("HIGH SCORE", rightX, highLabelY, panelLabelRightPaint)
        val highNumberY = highLabelY + dp(28f)
        canvas.drawText(highScore.toString(), rightX, highNumberY, panelNumberStrokePaint)
        canvas.drawText(highScore.toString(), rightX, highNumberY, panelNumberPaint)

        // ---- "NEW HIGH SCORE!" banner under the panel (gentle pulse) ----
        if (isNewHighScore) {
            val pulse = 1f + 0.08f * sin(gameOverElapsed * 6f)
            val msgY = panelBottom + dp(48f)
            canvas.save()
            canvas.scale(pulse, pulse, cx, msgY)
            canvas.drawText("NEW HIGH SCORE!", cx, msgY, newHighScoreStrokePaint)
            canvas.drawText("NEW HIGH SCORE!", cx, msgY, newHighScorePaint)
            canvas.restore()
        }

        // ---- "TAP TO RESTART" — gated by RESTART_DELAY ----
        if (gameOverElapsed >= GameConfig.RESTART_DELAY) {
            val restartY = min(safeBottom() - dp(40f), screenH * 0.85f)
            canvas.drawText("TAP TO RESTART", cx, restartY, labelStrokePaint)
            canvas.drawText("TAP TO RESTART", cx, restartY, labelPaint)
        }
    }

    /**
     * Draws the medal slot. Empty (pale green disc with darker ring) when
     * the player hasn't cleared the bronze threshold; otherwise a colored
     * disc with a soft shine highlight.
     */
    private fun drawMedal(canvas: Canvas, cx: Float, cy: Float, r: Float, medal: Medal) {
        // Outer ring (darker green) — always drawn
        canvas.drawCircle(cx, cy, r, medalRingPaint)
        val innerR = r - dp(5f)
        val fill = when (medal) {
            Medal.NONE -> medalEmptyPaint
            Medal.BRONZE -> medalBronzePaint
            Medal.SILVER -> medalSilverPaint
            Medal.GOLD -> medalGoldPaint
        }
        canvas.drawCircle(cx, cy, innerR, fill)
        // Shine highlight on earned medals only
        if (medal != Medal.NONE) {
            val highlightR = innerR * 0.35f
            canvas.drawCircle(cx - innerR * 0.32f, cy - innerR * 0.32f, highlightR, medalShinePaint)
        }
    }

    // =====================================================================
    //  Input — taps are in physical pixels; world coordinates don't matter.
    // =====================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            handleTap()
            return true
        }
        return super.onTouchEvent(event)
    }

    @Synchronized
    private fun handleTap() {
        if (!::bird.isInitialized) return
        when (state) {
            GameState.Ready -> {
                state = GameState.Playing
                bird.flap()
                sound.playFlap()
                sound.startMusic()
            }
            GameState.Playing -> {
                bird.flap()
                sound.playFlap()
            }
            GameState.GameOver -> {
                if (gameOverElapsed >= GameConfig.RESTART_DELAY) {
                    initWorld()  // initWorld() also un-ducks music back to MUSIC_VOLUME
                }
            }
        }
    }

    // =====================================================================
    //  Thread management
    // =====================================================================

    private fun startGameThread() {
        val existing = gameThread
        if (existing != null && existing.isAlive) return
        val t = GameThread(holder, this)
        gameThread = t
        t.running = true
        t.start()
    }

    private fun stopGameThread() {
        val t = gameThread ?: return
        t.running = false
        var joined = false
        while (!joined) {
            try {
                t.join()
                joined = true
            } catch (_: InterruptedException) {
                // Retry the join — the thread must be fully dead before the
                // surface is torn down, else lockCanvas may crash next time.
            }
        }
        gameThread = null
    }
}

/**
 * Dedicated game loop thread (fixed-timestep accumulator pattern).
 */
private class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView
) : Thread("TappyBird-GameLoop") {

    @Volatile
    var running: Boolean = false

    override fun run() {
        var lastNs = System.nanoTime()
        var accumulator = 0f
        val targetFrameNs = (1_000_000_000L / 60)

        while (running) {
            val now = System.nanoTime()
            val realDt = ((now - lastNs) / 1_000_000_000f).coerceAtMost(GameConfig.MAX_FRAME_DT)
            lastNs = now

            accumulator += realDt
            while (accumulator >= GameConfig.FIXED_DT) {
                gameView.update(GameConfig.FIXED_DT)
                accumulator -= GameConfig.FIXED_DT
            }

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        gameView.render(canvas)
                    }
                }
            } catch (_: IllegalStateException) {
                // Surface went away between isValid and lock — ignore.
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: IllegalStateException) {
                    }
                }
            }

            val frameElapsedNs = System.nanoTime() - now
            val sleepNs = targetFrameNs - frameElapsedNs
            if (sleepNs > 0) {
                try {
                    sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                }
            }
        }
    }
}
