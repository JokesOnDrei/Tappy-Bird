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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dreideas.tappybird.audio.SoundManager
import com.dreideas.tappybird.entities.Bird
import com.dreideas.tappybird.entities.PipePair
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

    // World paints
    private val skyPaint = Paint().apply { color = Color.rgb(112, 197, 206) }
    private val pipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 175, 73) }
    private val pipeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 140, 50) }
    private val pipeLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 195, 80) }
    private val groundPaint = Paint().apply { color = Color.rgb(221, 216, 148) }
    private val groundStripeAPaint = Paint().apply { color = Color.rgb(206, 199, 130) }
    private val groundTopPaint = Paint().apply { color = Color.rgb(90, 175, 73) }
    private val birdBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(241, 213, 76) }
    private val birdOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val birdBeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 115, 40) }
    private val birdEyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val birdEyePupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val birdWingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(225, 185, 50) }

    // UI paints (dp-scaled at construction time)
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(72f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val scoreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = dp(72f)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(56f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val titleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = dp(56f)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(36f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = dp(36f)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(22f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallLabelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = dp(22f)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); style = Paint.Style.STROKE; strokeWidth = dp(3f)
    }
    private val panelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); textAlign = Paint.Align.CENTER
        textSize = dp(28f); typeface = Typeface.DEFAULT_BOLD
    }
    private val newHighScorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 205, 40); textAlign = Paint.Align.CENTER
        textSize = dp(32f); typeface = Typeface.DEFAULT_BOLD
    }
    private val newHighScoreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); textAlign = Paint.Align.CENTER
        textSize = dp(32f); style = Paint.Style.STROKE; strokeWidth = dp(3f)
        typeface = Typeface.DEFAULT_BOLD
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
        if (bird.y - bird.radius < 0f) {
            bird.y = bird.radius
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
        val restingY = groundTopY - bird.radius
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
        if (bird.y + bird.radius >= groundTopY) return HitKind.GROUND

        for (pair in pipePairs) {
            if (pair.x + pair.width < bird.x - bird.radius) continue
            if (pair.x > bird.x + bird.radius) continue
            if (circleIntersectsRect(
                    bird.x, bird.y, bird.radius,
                    pair.topPipeLeft(), pair.topPipeTop(),
                    pair.topPipeRight(), pair.topPipeBottom()
                )
            ) return HitKind.PIPE
            if (circleIntersectsRect(
                    bird.x, bird.y, bird.radius,
                    pair.bottomPipeLeft(), pair.bottomPipeTop(),
                    pair.bottomPipeRight(), pair.bottomPipeBottom(groundTopY)
                )
            ) return HitKind.PIPE
        }
        return HitKind.NONE
    }

    private fun circleIntersectsRect(
        cx: Float, cy: Float, r: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ): Boolean {
        val nearestX = max(left, min(cx, right))
        val nearestY = max(top, min(cy, bottom))
        val dx = cx - nearestX
        val dy = cy - nearestY
        return (dx * dx + dy * dy) <= (r * r)
    }

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
        val lipOverhang = 8f
        val lipHeight = 36f
        for (pair in pipePairs) {
            canvas.drawRect(
                pair.topPipeLeft(), pair.topPipeTop(),
                pair.topPipeRight(), pair.topPipeBottom(), pipePaint
            )
            canvas.drawRect(
                pair.topPipeRight() - 10f, pair.topPipeTop(),
                pair.topPipeRight(), pair.topPipeBottom(), pipeShadowPaint
            )
            canvas.drawRect(
                pair.topPipeLeft() - lipOverhang,
                pair.topPipeBottom() - lipHeight,
                pair.topPipeRight() + lipOverhang,
                pair.topPipeBottom(),
                pipeLipPaint
            )

            canvas.drawRect(
                pair.bottomPipeLeft(), pair.bottomPipeTop(),
                pair.bottomPipeRight(), pair.bottomPipeBottom(groundTopY), pipePaint
            )
            canvas.drawRect(
                pair.bottomPipeRight() - 10f, pair.bottomPipeTop(),
                pair.bottomPipeRight(), pair.bottomPipeBottom(groundTopY), pipeShadowPaint
            )
            canvas.drawRect(
                pair.bottomPipeLeft() - lipOverhang,
                pair.bottomPipeTop(),
                pair.bottomPipeRight() + lipOverhang,
                pair.bottomPipeTop() + lipHeight,
                pipeLipPaint
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
        val r = bird.radius
        canvas.save()
        canvas.rotate(bird.rotationDegrees, bird.x, bird.y)

        canvas.drawCircle(bird.x, bird.y, r, birdBodyPaint)
        canvas.drawCircle(bird.x, bird.y, r, birdOutlinePaint)

        val wingPhase = sin(totalElapsed * 10f + bird.y * 0.02f)
        val wingH = r * 0.35f * (0.7f + 0.3f * wingPhase)
        tmpRect.set(
            bird.x - r * 0.5f,
            bird.y - wingH,
            bird.x + r * 0.3f,
            bird.y + wingH
        )
        canvas.drawOval(tmpRect, birdWingPaint)
        canvas.drawOval(tmpRect, birdOutlinePaint)

        canvas.drawCircle(bird.x + r * 0.35f, bird.y - r * 0.35f, r * 0.22f, birdEyeWhitePaint)
        canvas.drawCircle(bird.x + r * 0.35f, bird.y - r * 0.35f, r * 0.22f, birdOutlinePaint)
        canvas.drawCircle(bird.x + r * 0.42f, bird.y - r * 0.32f, r * 0.10f, birdEyePupilPaint)

        tmpRect.set(
            bird.x + r * 0.55f, bird.y - r * 0.18f,
            bird.x + r + 14f, bird.y + r * 0.18f
        )
        canvas.drawRect(tmpRect, birdBeakPaint)
        canvas.drawRect(tmpRect, birdOutlinePaint)

        canvas.restore()
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

        val headerY = max(safeTop() + dp(72f), screenH * 0.22f)
        canvas.drawText("GAME OVER", cx, headerY, titleStrokePaint)
        canvas.drawText("GAME OVER", cx, headerY, titlePaint)

        val panelW = min(dp(300f), screenW * 0.8f)
        val panelH = dp(130f)
        val panelLeft = cx - panelW / 2f
        val panelTop = headerY + dp(50f)
        tmpRect.set(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH)
        val corner = dp(12f)
        canvas.drawRoundRect(tmpRect, corner, corner, panelPaint)
        canvas.drawRoundRect(tmpRect, corner, corner, panelStrokePaint)

        canvas.drawText("SCORE: $score", cx, panelTop + panelH * 0.38f, panelTextPaint)
        canvas.drawText("BEST: $highScore", cx, panelTop + panelH * 0.78f, panelTextPaint)

        if (isNewHighScore) {
            val pulse = 1f + 0.08f * sin(gameOverElapsed * 6f)
            val msgY = panelTop + panelH + dp(60f)
            canvas.save()
            canvas.scale(pulse, pulse, cx, msgY)
            val msg = "NEW HIGH SCORE!"
            canvas.drawText(msg, cx, msgY, newHighScoreStrokePaint)
            canvas.drawText(msg, cx, msgY, newHighScorePaint)
            canvas.restore()
        }

        if (gameOverElapsed >= GameConfig.RESTART_DELAY) {
            val restartY = min(safeBottom() - dp(40f), screenH * 0.82f)
            canvas.drawText("TAP TO RESTART", cx, restartY, labelStrokePaint)
            canvas.drawText("TAP TO RESTART", cx, restartY, labelPaint)
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
