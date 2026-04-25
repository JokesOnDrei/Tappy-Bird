package com.dreideas.tappybird

/**
 * Single source of truth for all tunable game constants.
 *
 * Units: **world units (wu)** and seconds. Velocities are wu/s, accelerations
 * wu/s². A "world unit" is the abstract coordinate the game logic runs in;
 * GameView computes a per-device scale+offset and maps world → screen pixels
 * at render time so physics feels identical on every device.
 *
 * The reference world is 1080 × 1920 wu (9:16). On devices with that exact
 * aspect, 1 wu = 1 px. On taller or wider devices the playable world is
 * extended (not letterboxed) — see GameView.recomputeScaling().
 *
 * Values are tuned for engaged gameplay on the reference world; because the
 * physics integrator is frame-rate independent they also behave identically
 * at 30 / 60 / 120 FPS.
 */
object GameConfig {

    // -------- World reference size --------
    /**
     * Reference world width in world units. All gameplay X values are
     * interpreted relative to this; the actual playable width may be larger
     * on wide devices (see GameView.worldW).
     */
    const val WORLD_WIDTH: Float = 1080f

    /**
     * Reference world height in world units. The actual playable height may
     * be larger on tall devices (see GameView.worldH).
     */
    const val WORLD_HEIGHT: Float = 1920f

    // -------- Physics --------
    /** Constant downward acceleration applied every frame. */
    const val GRAVITY: Float = 2000f

    /**
     * Upward velocity set on each tap (Option A — velocity reset).
     *
     * We OVERWRITE velocityY rather than adding, which matches the classic
     * Flappy Bird behavior: every tap gives consistent, predictable lift
     * regardless of how fast the bird was falling.
     *
     * Value derived from tap-rate analysis: at 6 tps (Δt=0.167s) this yields
     * a sustained climb rate of 433 px/s — enough to navigate a 400-px
     * gap-center delta between pipes with a ~30% safety margin. Single-tap
     * peak rise is 90 px (~5% of screen height) so reflex taps never
     * surprise the player.
     */
    const val FLAP_IMPULSE: Float = -600f

    /** Terminal velocity (positive Y is downward on Android canvas). */
    const val MAX_FALL_SPEED: Float = 1200f

    /** Cap on how fast the bird can be traveling upward. */
    const val MAX_RISE_SPEED: Float = -660f

    // -------- Bird --------
    const val BIRD_RADIUS: Float = 40f
    /** Horizontal position of the bird as a fraction of the screen width. */
    const val BIRD_X_FRACTION: Float = 0.35f
    /** Bobbing amplitude (px) while in READY state. */
    const val BIRD_BOB_AMPLITUDE: Float = 20f
    /** Bobbing angular frequency (rad/s) while in READY state. */
    const val BIRD_BOB_FREQUENCY: Float = 3f

    // -------- Pipes --------
    const val GAP_HEIGHT: Float = 280f     // Vertical opening the bird flies through
    const val PIPE_WIDTH: Float = 190f     // Width of each pipe rectangle
    const val PIPE_SPACING: Float = 570f   // Horizontal distance between pipe pairs
    const val SCROLL_SPEED: Float = 250f   // Leftward world scroll speed (px/s)
    const val PIPE_POOL_SIZE: Int = 4      // Number of recycled pipe pairs
    /** Minimum distance from top / ground that the gap center may occupy. */
    const val PIPE_GAP_MARGIN: Float = 100f

    /**
     * Maximum allowed change in gap center Y between consecutive pipe pairs.
     *
     * Derived from the 5-tps reach limit: at FLAP_IMPULSE=-600, a player
     * sustaining 5 taps/second climbs 400 px/s. Over the 1.52 s of clean
     * air between pipes plus 200 px of in-gap vertical slack, the reachable
     * delta is 808 px. We clamp to 800 so the hardest generated transition
     * sits right at the 5-tps skill threshold — reachable by a median player
     * with precise timing, easy for skilled (6+ tps) players.
     */
    const val MAX_GAP_DELTA: Float = 800f

    // -------- Ground --------
    const val GROUND_HEIGHT: Float = 160f
    const val GROUND_STRIPE_WIDTH: Float = 40f

    // -------- Loop --------
    /** Fixed timestep for the update loop (1/60 s). Render is decoupled. */
    const val FIXED_DT: Float = 1f / 60f
    /** Clamp on real elapsed time per frame to prevent "spiral of death". */
    const val MAX_FRAME_DT: Float = 0.05f

    // -------- Game-over --------
    /** Minimum time (s) after game-over before restart tap is accepted. */
    const val RESTART_DELAY: Float = 0.6f

    // -------- Persistence --------
    const val PREFS_FILE: String = "game_prefs"
    const val KEY_HIGH_SCORE: String = "flappy_high_score"
}
