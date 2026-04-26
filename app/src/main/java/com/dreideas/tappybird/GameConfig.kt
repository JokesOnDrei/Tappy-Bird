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
    /**
     * Visual reference radius. Drives sprite scale in [GameView.drawBird]:
     * `pixelSize = (BIRD_RADIUS * 2) / BirdSprite.widthCells`. Not used for
     * collision — see [BIRD_COLLISION_HALF_WIDTH] / [BIRD_COLLISION_HALF_HEIGHT].
     */
    const val BIRD_RADIUS: Float = 60f

    /**
     * Bird collision is **AABB**, not circle. The visible bird sprite is
     * 17×12 cells (wider than tall); a bounding circle hugely over-reaches
     * at the transparent corners. These half-extents match the sprite's
     * solid silhouette, slightly inset (~5%) so the empty corner cells
     * don't trigger a hit.
     *
     * Result: a pipe registers a collision only when it actually overlaps
     * a visible part of the bird, not when it merely enters the bounding
     * circle. Beak protrusion IS included — the beak is visible.
     */
    const val BIRD_COLLISION_HALF_WIDTH: Float = 56f   // sprite half-width 60, inset to 56
    const val BIRD_COLLISION_HALF_HEIGHT: Float = 40f  // sprite half-height ~42, inset to 40

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
    const val GROUND_HEIGHT: Float = 240f
    const val GROUND_STRIPE_WIDTH: Float = 60f

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

    // -------- Audio --------
    /**
     * Master mute. Dev convenience only — no UI per CLAUDE.md §8.
     * Flip to `true` while iterating gameplay if the SFX gets old.
     */
    const val AUDIO_MUTED: Boolean = false

    /**
     * SoundPool concurrent stream cap. 4 covers the worst overlap:
     * background music + flap + score + hit firing in the same frame.
     * Higher values waste audio buffers; lower may clip the score "ding"
     * if it lands on the same frame as a flap and a hit.
     */
    const val SOUND_POOL_MAX_STREAMS: Int = 4

    /** Flap SFX gain (0..1). Mid-loud — taps are core feedback. */
    const val SFX_FLAP_VOLUME: Float = 0.15f

    /** Score "ding" gain (0..1). Bright but not dominant. */
    const val SFX_SCORE_VOLUME: Float = 0.05f

    /** Pipe-collision hit gain (0..1). Punchy — sells the failure. */
    const val SFX_HIT_VOLUME: Float = 0.9f

    /** Ground-impact thud gain (0..1). Softer than the hit; it's the outro. */
    const val SFX_FALL_VOLUME: Float = 0.6f

    /**
     * Background music base volume (0..1). Quiet enough to not fight the
     * SFX bed; the brief calls for "ambient, not tense."
     */
    const val MUSIC_VOLUME: Float = 0.35f

    /**
     * Ducked music volume during GameOver. ~⅓ of base — audible but recedes
     * so the score panel reads as a calm pause rather than a continuation.
     */
    const val MUSIC_DUCK_VOLUME: Float = 0.12f

    /**
     * Audio filename stems in `res/raw/`. SoundManager resolves these via
     * `Resources.getIdentifier` so missing files degrade gracefully — the
     * game still runs (silent) even if assets haven't been added yet.
     *
     * Drop files into `app/src/main/res/raw/` matching these names; OGG
     * preferred (smaller), WAV acceptable. See README "Audio assets".
     */
    const val SFX_FLAP_RES: String = "sfx_flap"
    const val SFX_SCORE_RES: String = "sfx_score"
    const val SFX_HIT_RES: String = "sfx_hit"
    const val SFX_FALL_RES: String = "sfx_fall"
    const val MUSIC_RES: String = "music_loop"

    // -------- Typography --------
    /**
     * UI font resources, expected at `app/src/main/res/font/`.
     *
     * The naming distinguishes two weight roles (title vs. body) so the
     * font system supports two-tier hierarchy when the chosen face has
     * multiple weights. The current font (Jersey) only has one weight,
     * so both constants resolve to the same file — that's fine; the
     * loader returns the same Typeface to both paints.
     *
     * Resolved at runtime by name via `Resources.getIdentifier` so missing
     * files degrade gracefully — the game still runs (with a system-default
     * fallback) even if no font has been installed.
     *
     * Filenames in res/font/ MUST be lowercase letters/digits/underscores
     * only — Android's resource compiler rejects hyphens.
     */
    const val PIXEL_FONT_TITLE_RES: String = "jersey_regular"
    const val PIXEL_FONT_BODY_RES: String = "jersey_regular"

    // -------- Medals --------
    /**
     * Score thresholds for end-of-game medals shown in the Game Over panel.
     * Tuned against the difficulty curve from the physics analysis (see
     * README "Predicted performance"): casual players (~4 pipes median)
     * almost never see a medal, average players (~12) earn bronze, skilled
     * players reliably hit gold.
     */
    const val MEDAL_BRONZE_THRESHOLD: Int = 10
    const val MEDAL_SILVER_THRESHOLD: Int = 20
    const val MEDAL_GOLD_THRESHOLD: Int = 30
}
