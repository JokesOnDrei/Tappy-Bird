package com.dreideas.tappybird.entities

import com.dreideas.tappybird.GameConfig

/**
 * The player-controlled bird.
 *
 * All position, velocity, and size fields are in **world units** (see
 * GameConfig). GameView applies the world → screen transform at render
 * time, so this class is resolution- and DPI-independent.
 *
 * X is fixed (the world scrolls past), Y is integrated from velocity.
 * Rotation is derived from velocity for game-feel: nose-up while rising,
 * nose-down while falling fast.
 */
class Bird(
    var x: Float,
    var y: Float
) {
    var velocityY: Float = 0f
    val radius: Float = GameConfig.BIRD_RADIUS
    var rotationDegrees: Float = 0f

    /**
     * Physics integration — semi-implicit Euler.
     *
     *   v_new = clamp(v + g * dt,  MAX_RISE, MAX_FALL)
     *   y_new = y + v_new * dt
     *
     * Clamping the velocity BEFORE integrating position makes the
     * terminal-velocity cap actually bite in one frame instead of two.
     */
    fun update(dt: Float) {
        velocityY += GameConfig.GRAVITY * dt
        velocityY = velocityY.coerceIn(GameConfig.MAX_RISE_SPEED, GameConfig.MAX_FALL_SPEED)
        y += velocityY * dt
        updateRotationFromVelocity()
    }

    /**
     * Apply a velocity-RESET flap (Option A — classic Flappy Bird).
     *
     * Overwrites velocityY so every tap gives the same predictable lift
     * regardless of the bird's current falling speed. This makes recovery
     * from a deep fall consistent and skill-driven rather than requiring
     * the player to mash 4–5 taps to cancel accumulated downward velocity.
     * The rise cap is still applied to keep behavior well-defined.
     */
    fun flap() {
        velocityY = GameConfig.FLAP_IMPULSE
        velocityY = velocityY.coerceIn(GameConfig.MAX_RISE_SPEED, GameConfig.MAX_FALL_SPEED)
    }

    /**
     * Map velocity onto a pleasing rotation range:
     * at MAX_RISE_SPEED  → -30° (nose up)
     * at MAX_FALL_SPEED  → +90° (straight down)
     */
    private fun updateRotationFromVelocity() {
        val span = GameConfig.MAX_FALL_SPEED - GameConfig.MAX_RISE_SPEED
        val t = ((velocityY - GameConfig.MAX_RISE_SPEED) / span).coerceIn(0f, 1f)
        rotationDegrees = -30f + t * 120f
    }
}
