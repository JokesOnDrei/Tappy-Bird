package com.dreideas.tappybird

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dreideas.tappybird.sprites.BirdSprite
import com.dreideas.tappybird.sprites.PipeSprite
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Splash-screen view. Plays a short scripted animation (bird flies → hits
 * a pipe → falls → title appears) using the same [BirdSprite] / [PipeSprite]
 * assets as the gameplay scene, then waits for a tap to launch the game.
 *
 * Independent from [GameView]: simpler animation thread, no physics, no
 * audio. Uses screen-pixel coordinates directly (no world-unit transform)
 * because the splash is one-shot and doesn't need device-aspect parity
 * with the game.
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val onLaunchGame: (() -> Unit)? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var thread: SplashThread? = null

    private var screenW: Float = 0f
    private var screenH: Float = 0f

    private var safeAreaTop: Float = 0f
    private var safeAreaBottom: Float = 0f

    /** Seconds since the splash started rendering. */
    private var elapsed: Float = 0f

    /** Latched once a launch tap is accepted, so a second tap is a no-op. */
    private var launched: Boolean = false

    // -- Phase timings (seconds) -----------------------------------------

    private val PHASE_FLY_END = 1.2f
    private val PHASE_COLLIDE_END = 1.4f
    private val PHASE_FALL_END = 2.0f
    private val PHASE_TITLE_END = 2.5f

    // -- Density / fonts -------------------------------------------------

    private val density: Float = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val pixelFont: Typeface = run {
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier(
            GameConfig.PIXEL_FONT_TITLE_RES, "font", context.packageName
        )
        if (id == 0) Typeface.DEFAULT_BOLD
        else try {
            ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT_BOLD
        } catch (_: Resources.NotFoundException) {
            Typeface.DEFAULT_BOLD
        }
    }

    // -- Paints ----------------------------------------------------------

    private val skyPaint = Paint().apply { color = Color.rgb(112, 197, 206) }
    private val groundPaint = Paint().apply { color = Color.rgb(221, 216, 148) }
    private val groundTopPaint = Paint().apply { color = Color.rgb(90, 175, 73) }
    private val flashPaint = Paint().apply { color = Color.argb(0, 255, 255, 255) }

    private val titleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(252, 152, 30)
        textAlign = Paint.Align.CENTER
        textSize = dp(80f)
        typeface = pixelFont
    }
    private val titleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(95, 50, 15)
        textAlign = Paint.Align.CENTER
        textSize = dp(80f)
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        strokeJoin = Paint.Join.ROUND
        typeface = pixelFont
    }
    private val tapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(34f)
        typeface = pixelFont
    }
    private val tapStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(60, 35, 15)
        textAlign = Paint.Align.CENTER
        textSize = dp(34f)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeJoin = Paint.Join.ROUND
        typeface = pixelFont
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        setupInsets()
    }

    // ====================================================================
    //  Insets / lifecycle
    // ====================================================================

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            safeAreaTop = max(bars.top, cutout.top).toFloat()
            safeAreaBottom = max(bars.bottom, cutout.bottom).toFloat()
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { startThread() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        screenW = w.toFloat()
        screenH = h.toFloat()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { stopThread() }

    fun pause() { stopThread() }
    fun resume() { if (holder.surface?.isValid == true) startThread() }

    // ====================================================================
    //  Update / render
    // ====================================================================

    fun update(dt: Float) { elapsed += dt }

    fun render(canvas: Canvas) {
        if (screenW <= 0f || screenH <= 0f) return

        // ---- Layout ----
        val groundH = dp(80f)
        val groundY = screenH - groundH

        val pipeWidth = screenW * 0.22f
        val pipeLeft = screenW * 0.62f
        val pipeRight = pipeLeft + pipeWidth
        val pipeBottom = screenH * 0.50f

        val birdScreenWidth = screenW * 0.25f
        val birdPixelSize = birdScreenWidth / BirdSprite.widthCells
        val birdHeight = BirdSprite.heightCells * birdPixelSize

        // ---- Screen shake during collision ----
        val shake = if (elapsed in PHASE_FLY_END..PHASE_COLLIDE_END) {
            val t = ((elapsed - PHASE_FLY_END) /
                (PHASE_COLLIDE_END - PHASE_FLY_END)).coerceIn(0f, 1f)
            sin(t * 35f) * dp(10f) * (1f - t)
        } else 0f

        canvas.save()
        canvas.translate(shake, 0f)

        // Sky
        canvas.drawRect(0f, 0f, screenW, screenH, skyPaint)

        // Pipe (top pipe, lip near mid-screen)
        PipeSprite.renderTopPipe(canvas, pipeLeft, 0f, pipeRight, pipeBottom)

        // Ground
        canvas.drawRect(0f, groundY, screenW, groundY + dp(8f), groundTopPaint)
        canvas.drawRect(0f, groundY + dp(8f), screenW, screenH, groundPaint)

        // Bird
        val (birdX, birdY, birdRot) = computeBirdPose(
            pipeLeft, groundY, birdScreenWidth, birdHeight
        )
        BirdSprite.render(canvas, birdX, birdY, birdPixelSize, birdRot)

        canvas.restore()  // end shake transform

        // ---- White flash on impact ----
        val flashFadeEnd = PHASE_COLLIDE_END + 0.15f
        if (elapsed in PHASE_FLY_END..flashFadeEnd) {
            val t = ((elapsed - PHASE_FLY_END) /
                (flashFadeEnd - PHASE_FLY_END)).coerceIn(0f, 1f)
            val alpha = ((1f - t) * 200f).toInt().coerceIn(0, 255)
            if (alpha > 0) {
                flashPaint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawRect(0f, 0f, screenW, screenH, flashPaint)
            }
        }

        // ---- Title (scale-in with overshoot) ----
        if (elapsed >= PHASE_FALL_END) {
            val t = ((elapsed - PHASE_FALL_END) /
                (PHASE_TITLE_END - PHASE_FALL_END)).coerceIn(0f, 1f)
            // Ease-out-back curve: peaks at ~1.15, settles at 1.0
            val scale = if (t < 1f) 1f + 0.20f * sin(t * Math.PI.toFloat()) else 1f
            val titleY = max(safeAreaTop + dp(140f), screenH * 0.22f)
            canvas.save()
            canvas.scale(scale, scale, screenW / 2f, titleY)
            canvas.drawText("TAPPY BIRD", screenW / 2f, titleY, titleStrokePaint)
            canvas.drawText("TAPPY BIRD", screenW / 2f, titleY, titleFillPaint)
            canvas.restore()
        }

        // ---- "TAP TO PLAY" — pulses gently ----
        if (elapsed >= PHASE_TITLE_END) {
            val pulse = (sin((elapsed - PHASE_TITLE_END) * 4f) + 1f) / 2f  // 0..1
            val alpha = (180 + pulse * 75).toInt().coerceIn(0, 255)
            tapFillPaint.alpha = alpha
            tapStrokePaint.alpha = alpha
            val tapY = min(screenH - safeAreaBottom - dp(80f), screenH * 0.85f)
            canvas.drawText("TAP TO PLAY", screenW / 2f, tapY, tapStrokePaint)
            canvas.drawText("TAP TO PLAY", screenW / 2f, tapY, tapFillPaint)
        }
    }

    /** Returns (x, y, rotationDegrees) for the bird at the current [elapsed]. */
    private fun computeBirdPose(
        pipeLeft: Float,
        groundY: Float,
        birdW: Float,
        birdH: Float
    ): Triple<Float, Float, Float> {
        // Collision point: bird's right edge just touches the pipe's left.
        val impactX = pipeLeft - birdW * 0.4f
        val flightY = screenH * 0.42f

        return when {
            elapsed < PHASE_FLY_END -> {
                val t = elapsed / PHASE_FLY_END
                val startX = -birdW
                val x = startX + t * (impactX - startX)
                val y = flightY + sin(elapsed * 8f) * dp(20f)
                val rot = cos(elapsed * 8f) * 12f
                Triple(x, y, rot)
            }
            elapsed < PHASE_COLLIDE_END -> {
                // Bird stuck against pipe, slight forward-tilt from impact.
                Triple(impactX, flightY, 25f)
            }
            elapsed < PHASE_FALL_END -> {
                // Quadratic fall + small backward bounce + spin.
                val tFall = ((elapsed - PHASE_COLLIDE_END) /
                    (PHASE_FALL_END - PHASE_COLLIDE_END)).coerceIn(0f, 1f)
                val x = impactX - tFall * dp(40f)
                val endY = groundY - birdH * 0.45f
                val y = flightY + tFall * tFall * (endY - flightY)
                val rot = 25f + tFall * 270f
                Triple(x, y, rot)
            }
            else -> {
                // Settled on the ground, lying on back.
                Triple(impactX - dp(40f), groundY - birdH * 0.45f, 90f)
            }
        }
    }

    // ====================================================================
    //  Input
    // ====================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        when {
            launched -> Unit
            elapsed >= PHASE_TITLE_END -> {
                launched = true
                onLaunchGame?.invoke()
            }
            else -> {
                // Skip the animation: jump straight to title reveal.
                elapsed = PHASE_FALL_END
            }
        }
        return true
    }

    // ====================================================================
    //  Thread management
    // ====================================================================

    private fun startThread() {
        if (thread?.isAlive == true) return
        val t = SplashThread(holder, this)
        thread = t
        t.running = true
        t.start()
    }

    private fun stopThread() {
        val t = thread ?: return
        t.running = false
        var joined = false
        while (!joined) {
            try { t.join(); joined = true }
            catch (_: InterruptedException) { /* retry */ }
        }
        thread = null
    }
}

private class SplashThread(
    private val surfaceHolder: SurfaceHolder,
    private val view: SplashView
) : Thread("TappyBird-Splash") {

    @Volatile
    var running: Boolean = false

    override fun run() {
        var lastNs = System.nanoTime()
        val targetFrameNs = 1_000_000_000L / 60
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
            lastNs = now

            view.update(dt)

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) { view.render(canvas) }
                }
            } catch (_: IllegalStateException) {
                // Surface gone — swallow.
            } finally {
                if (canvas != null) {
                    try { surfaceHolder.unlockCanvasAndPost(canvas) }
                    catch (_: IllegalStateException) { /* swallow */ }
                }
            }

            val frameElapsed = System.nanoTime() - now
            val sleepNs = targetFrameNs - frameElapsed
            if (sleepNs > 0) {
                try { sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt()) }
                catch (_: InterruptedException) { /* loop checks running */ }
            }
        }
    }
}
