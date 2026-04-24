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
import com.dreideas.tappybird.entities.Bird
import com.dreideas.tappybird.entities.PipePair
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The game's SurfaceView. Owns:
 *   - the world state (bird, pipe pool, score, game state)
 *   - the render thread (fixed-timestep update + decoupled render)
 *   - touch input handling
 *
 * Rendering is done with primitive Canvas shapes (no external assets).
 * Sprites can be swapped in later by replacing the draw* methods.
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

    private var state: GameState = GameState.Ready
    private var score: Int = 0
    private var highScore: Int = highScoreRepo.getHighScore()
    private var isNewHighScore: Boolean = false

    private var screenW: Float = 0f
    private var screenH: Float = 0f
    private var groundTopY: Float = 0f

    private lateinit var bird: Bird
    private val pipePairs: MutableList<PipePair> = ArrayList(GameConfig.PIPE_POOL_SIZE)

    /** Accumulated seconds in READY state — drives the bobbing animation. */
    private var readyElapsed: Float = 0f
    /** Seconds since game-over — gates the restart tap. */
    private var gameOverElapsed: Float = 0f
    /** Monotonic seconds since world init — drives cosmetic animations (wing flap). */
    private var totalElapsed: Float = 0f
    /** Running offset (0..stripeWidth) for the scrolling ground. */
    private var groundOffset: Float = 0f

    private val rng = Random(System.nanoTime())

    // ---------------------------------------------------------------------
    //  Paints (allocated once, reused across frames — no per-frame GC)
    // ---------------------------------------------------------------------

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

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 140f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val scoreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 140f
        style = Paint.Style.STROKE
        strokeWidth = 8f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 60f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 60f
        style = Paint.Style.STROKE
        strokeWidth = 6f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallLabelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 42f
        style = Paint.Style.STROKE
        strokeWidth = 5f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val panelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val panelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); textAlign = Paint.Align.CENTER
        textSize = 50f; typeface = Typeface.DEFAULT_BOLD
    }
    private val newHighScorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 205, 40); textAlign = Paint.Align.CENTER
        textSize = 56f; typeface = Typeface.DEFAULT_BOLD
    }
    private val newHighScoreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 45, 25); textAlign = Paint.Align.CENTER
        textSize = 56f; style = Paint.Style.STROKE; strokeWidth = 6f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val tmpRect = RectF()

    init {
        holder.addCallback(this)
        isFocusable = true
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
            groundTopY = screenH - GameConfig.GROUND_HEIGHT
            // Initialize the world on first sizing; preserve state on later
            // size callbacks (e.g. foreground/background) if we can.
            if (!::bird.isInitialized) {
                initWorld()
            } else {
                // Screen size changed — re-position entities proportionally.
                bird.x = screenW * GameConfig.BIRD_X_FRACTION
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopGameThread()
    }

    // =====================================================================
    //  Public lifecycle (called by host Activity)
    // =====================================================================

    fun pause() { stopGameThread() }

    fun resume() {
        if (holder.surface?.isValid == true) startGameThread()
    }

    // =====================================================================
    //  World (re)initialization
    // =====================================================================

    private fun initWorld() {
        bird = Bird(
            x = screenW * GameConfig.BIRD_X_FRACTION,
            y = screenH * 0.5f
        )
        pipePairs.clear()
        // First pair starts one screen-width past the right edge, subsequent
        // pairs are spaced at PIPE_SPACING — the pool fills naturally as
        // the world scrolls. Consecutive gap Ys are clamped so the vertical
        // traversal between pipes never exceeds MAX_GAP_DELTA.
        val firstX = screenW + GameConfig.PIPE_SPACING
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
        state = GameState.Ready
    }

    /**
     * @param prevGapY The previous pipe pair's gap center, or null for the
     *   very first pipe (no clamp applies). When supplied, the new gap is
     *   constrained to prevGapY ± MAX_GAP_DELTA so the bird can always
     *   physically traverse the vertical distance between consecutive pipes.
     */
    private fun randomGapY(prevGapY: Float? = null): Float {
        val halfGap = GameConfig.GAP_HEIGHT / 2f
        var minY = GameConfig.PIPE_GAP_MARGIN + halfGap
        var maxY = groundTopY - GameConfig.PIPE_GAP_MARGIN - halfGap
        if (prevGapY != null) {
            minY = max(minY, prevGapY - GameConfig.MAX_GAP_DELTA)
            maxY = min(maxY, prevGapY + GameConfig.MAX_GAP_DELTA)
        }
        // Safety: if the screen is tiny, collapse to center rather than NaN.
        if (maxY <= minY) return groundTopY * 0.5f
        return minY + rng.nextFloat() * (maxY - minY)
    }

    // =====================================================================
    //  Update step — called at fixed dt by GameThread
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
        // Bird bobs gently around screen center — no gravity applies yet.
        bird.y = screenH * 0.5f +
            sin(readyElapsed * GameConfig.BIRD_BOB_FREQUENCY) *
            GameConfig.BIRD_BOB_AMPLITUDE
        bird.velocityY = 0f
        bird.rotationDegrees = cos(readyElapsed * GameConfig.BIRD_BOB_FREQUENCY) * 5f
        advanceGround(dt)
    }

    private fun updatePlaying(dt: Float) {
        bird.update(dt)

        // Ceiling clamp: spec allows either death or a soft clamp; we clamp
        // so the player isn't punished for overshooting on a panicky tap.
        if (bird.y - bird.radius < 0f) {
            bird.y = bird.radius
            if (bird.velocityY < 0f) bird.velocityY = 0f
        }

        advanceGround(dt)

        // Scroll, score, and recycle pipes.
        for (pair in pipePairs) {
            pair.update(dt)

            // +1 the instant the bird's X passes the pair's right edge.
            if (!pair.scored && bird.x > pair.x + pair.width) {
                pair.scored = true
                score++
            }

            if (pair.isOffScreenLeft()) {
                // The rightmost pair is guaranteed not to be `pair` itself
                // (pair is off-screen left; POOL_SIZE ≥ 2 means at least one
                // other pair is still to the right).
                val rightmost = pipePairs.maxByOrNull { it.x }!!
                pair.recycle(
                    newX = rightmost.x + GameConfig.PIPE_SPACING,
                    newGapY = randomGapY(rightmost.gapY)
                )
            }
        }

        if (checkCollision()) enterGameOver()
    }

    private fun updateGameOver(dt: Float) {
        gameOverElapsed += dt
        // The bird continues to fall (gravity still applies) and spins until
        // it hits the ground, at which point we clamp it.
        val restingY = groundTopY - bird.radius
        if (bird.y < restingY) {
            bird.update(dt)
            // Add a spin independent of the physics-driven tilt.
            bird.rotationDegrees = min(bird.rotationDegrees + 540f * dt, 90f)
            if (bird.y >= restingY) {
                bird.y = restingY
                bird.velocityY = 0f
            }
        } else {
            bird.y = restingY
            bird.velocityY = 0f
        }
    }

    private fun advanceGround(dt: Float) {
        val stripe = GameConfig.GROUND_STRIPE_WIDTH
        groundOffset = (groundOffset - GameConfig.SCROLL_SPEED * dt) % stripe
        if (groundOffset > 0f) groundOffset -= stripe  // keep it in [-stripe, 0]
    }

    // ---------------------------------------------------------------------
    //  Collisions
    // ---------------------------------------------------------------------

    /** @return true if the bird has died this frame. */
    private fun checkCollision(): Boolean {
        // Ground: the whole bird-circle at/below the ground is fatal.
        if (bird.y + bird.radius >= groundTopY) return true

        for (pair in pipePairs) {
            // Skip pairs that are clearly out of horizontal range of the bird.
            if (pair.x + pair.width < bird.x - bird.radius) continue
            if (pair.x > bird.x + bird.radius) continue

            if (circleIntersectsRect(
                    bird.x, bird.y, bird.radius,
                    pair.topPipeLeft(), pair.topPipeTop(),
                    pair.topPipeRight(), pair.topPipeBottom()
                )
            ) return true
            if (circleIntersectsRect(
                    bird.x, bird.y, bird.radius,
                    pair.bottomPipeLeft(), pair.bottomPipeTop(),
                    pair.bottomPipeRight(), pair.bottomPipeBottom(groundTopY)
                )
            ) return true
        }
        return false
    }

    /**
     * Circle vs AABB hit-test: find the closest point on the rectangle to
     * the circle center, then compare squared distance against r^2.
     * (This is the standard technique — no sqrt on the hot path.)
     */
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

    // ---------------------------------------------------------------------
    //  State transitions
    // ---------------------------------------------------------------------

    private fun enterGameOver() {
        state = GameState.GameOver
        gameOverElapsed = 0f
        if (highScoreRepo.trySetHighScore(score)) {
            highScore = score
            isNewHighScore = true
        }
    }

    // =====================================================================
    //  Render — called once per real frame by GameThread
    // =====================================================================

    @Synchronized
    fun render(canvas: Canvas) {
        if (!::bird.isInitialized) {
            canvas.drawColor(Color.rgb(112, 197, 206))
            return
        }

        // Sky
        canvas.drawRect(0f, 0f, screenW, screenH, skyPaint)

        drawPipes(canvas)
        drawGround(canvas)
        drawBird(canvas)

        when (state) {
            GameState.Ready -> drawReadyOverlay(canvas)
            GameState.Playing -> drawScoreBig(canvas)
            GameState.GameOver -> drawGameOverOverlay(canvas)
        }
    }

    private fun drawPipes(canvas: Canvas) {
        val lipOverhang = 8f
        val lipHeight = 36f
        for (pair in pipePairs) {
            // Upper pipe body + lip
            canvas.drawRect(
                pair.topPipeLeft(), pair.topPipeTop(),
                pair.topPipeRight(), pair.topPipeBottom(), pipePaint
            )
            // thin shadow down the right edge — gives pseudo-3D depth.
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

            // Lower pipe body + lip
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
        // Thin dark-green strip at the very top of the ground.
        canvas.drawRect(0f, groundTopY, screenW, groundTopY + 12f, groundTopPaint)
        // Base sand.
        canvas.drawRect(0f, groundTopY + 12f, screenW, screenH, groundPaint)
        // Scrolling stripe pattern for parallax feedback.
        val stripe = GameConfig.GROUND_STRIPE_WIDTH
        var x = groundOffset
        while (x < screenW) {
            canvas.drawRect(
                x, groundTopY + 12f,
                x + stripe / 2f, screenH,
                groundStripeAPaint
            )
            x += stripe
        }
    }

    private fun drawBird(canvas: Canvas) {
        val r = bird.radius
        canvas.save()
        canvas.rotate(bird.rotationDegrees, bird.x, bird.y)

        // Body
        canvas.drawCircle(bird.x, bird.y, r, birdBodyPaint)
        canvas.drawCircle(bird.x, bird.y, r, birdOutlinePaint)

        // Wing — a simple filled ellipse that "flaps" via a time-based scale.
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

        // Eye
        canvas.drawCircle(bird.x + r * 0.35f, bird.y - r * 0.35f, r * 0.22f, birdEyeWhitePaint)
        canvas.drawCircle(bird.x + r * 0.35f, bird.y - r * 0.35f, r * 0.22f, birdOutlinePaint)
        canvas.drawCircle(bird.x + r * 0.42f, bird.y - r * 0.32f, r * 0.10f, birdEyePupilPaint)

        // Beak
        tmpRect.set(
            bird.x + r * 0.55f, bird.y - r * 0.18f,
            bird.x + r + 14f, bird.y + r * 0.18f
        )
        canvas.drawRect(tmpRect, birdBeakPaint)
        canvas.drawRect(tmpRect, birdOutlinePaint)

        canvas.restore()
    }

    private fun drawScoreBig(canvas: Canvas) {
        val y = screenH * 0.15f
        val text = score.toString()
        canvas.drawText(text, screenW / 2f, y, scoreStrokePaint)
        canvas.drawText(text, screenW / 2f, y, scorePaint)
    }

    private fun drawReadyOverlay(canvas: Canvas) {
        // "Tappy Bird" title
        val titleY = screenH * 0.22f
        canvas.drawText("TAPPY BIRD", screenW / 2f, titleY, scoreStrokePaint)
        canvas.drawText("TAPPY BIRD", screenW / 2f, titleY, scorePaint)

        // Tap to start
        val tapY = screenH * 0.75f
        canvas.drawText("TAP TO START", screenW / 2f, tapY, labelStrokePaint)
        canvas.drawText("TAP TO START", screenW / 2f, tapY, labelPaint)

        // High score (subtle)
        val hsY = screenH * 0.82f
        val hsText = "HIGH SCORE: $highScore"
        canvas.drawText(hsText, screenW / 2f, hsY, smallLabelStrokePaint)
        canvas.drawText(hsText, screenW / 2f, hsY, smallLabelPaint)
    }

    private fun drawGameOverOverlay(canvas: Canvas) {
        // Header
        val headerY = screenH * 0.22f
        canvas.drawText("GAME OVER", screenW / 2f, headerY, scoreStrokePaint)
        canvas.drawText("GAME OVER", screenW / 2f, headerY, scorePaint)

        // Result panel
        val panelW = screenW * 0.7f
        val panelH = screenH * 0.22f
        val panelLeft = (screenW - panelW) / 2f
        val panelTop = screenH * 0.32f
        tmpRect.set(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH)
        canvas.drawRoundRect(tmpRect, 24f, 24f, panelPaint)
        canvas.drawRoundRect(tmpRect, 24f, 24f, panelStrokePaint)

        val cx = screenW / 2f
        canvas.drawText("SCORE: $score", cx, panelTop + panelH * 0.38f, panelTextPaint)
        canvas.drawText("BEST: $highScore", cx, panelTop + panelH * 0.78f, panelTextPaint)

        if (isNewHighScore) {
            // Gentle pulse: text scales slightly with time so it draws the eye.
            val pulse = 1f + 0.08f * sin(gameOverElapsed * 6f)
            canvas.save()
            canvas.scale(pulse, pulse, cx, screenH * 0.60f)
            val msg = "NEW HIGH SCORE!"
            canvas.drawText(msg, cx, screenH * 0.60f, newHighScoreStrokePaint)
            canvas.drawText(msg, cx, screenH * 0.60f, newHighScorePaint)
            canvas.restore()
        }

        // Restart prompt — hidden until RESTART_DELAY elapsed.
        if (gameOverElapsed >= GameConfig.RESTART_DELAY) {
            val restartY = screenH * 0.72f
            canvas.drawText("TAP TO RESTART", cx, restartY, labelStrokePaint)
            canvas.drawText("TAP TO RESTART", cx, restartY, labelPaint)
        }
    }

    // =====================================================================
    //  Input
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
            }
            GameState.Playing -> bird.flap()
            GameState.GameOver -> {
                if (gameOverElapsed >= GameConfig.RESTART_DELAY) {
                    initWorld()
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
                // Retry the join — we need the thread fully dead before
                // the surface can be torn down, otherwise the next lock
                // attempt can crash.
            }
        }
        gameThread = null
    }
}

/**
 * Dedicated game loop thread.
 *
 * Uses a fixed-timestep accumulator pattern so physics stays identical at
 * 30/60/120 Hz:
 *
 *   accumulator += realDt
 *   while (accumulator >= FIXED_DT):
 *       update(FIXED_DT)
 *       accumulator -= FIXED_DT
 *   render()
 *
 * Real dt is clamped to MAX_FRAME_DT so a hitch never triggers a spiral
 * of physics catch-up that locks the UI.
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
                // Surface went away between isValid check and lock — ignore.
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: IllegalStateException) {
                        // Same as above — swallow.
                    }
                }
            }

            // Soft frame cap: if we finished well under the 60 Hz budget, yield
            // the rest of the slice so we don't spin the CPU.
            val frameElapsedNs = System.nanoTime() - now
            val sleepNs = targetFrameNs - frameElapsedNs
            if (sleepNs > 0) {
                try {
                    sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    // Interrupt during pause — loop will re-check `running`.
                }
            }
        }
    }
}
