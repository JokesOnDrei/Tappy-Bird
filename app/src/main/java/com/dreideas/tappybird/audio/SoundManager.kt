package com.dreideas.tappybird.audio

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.dreideas.tappybird.GameConfig

/**
 * Owns the game's audio output. Single instance per [com.dreideas.tappybird.GameView].
 *
 *  - **SFX** (flap, score, hit, fall) play through a shared [SoundPool] —
 *    cheap, low-latency, no allocations on the hot path.
 *  - **Background music** runs through a single looping [MediaPlayer]
 *    that's volume-adjustable (full → ducked) without restart.
 *
 * Resource resolution is **lazy and fault-tolerant**: filenames live in
 * [GameConfig] as strings; if a file is missing from `res/raw/` the load
 * is skipped silently and the relevant `play*()` becomes a no-op. The
 * game must run even with no audio assets installed (per spec).
 *
 * Threading: SoundPool and MediaPlayer are internally thread-safe for
 * `play()` / `setVolume()`, so callers don't need extra locks. Trigger
 * sites in GameView already run under `@Synchronized(this)`.
 */
class SoundManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(GameConfig.SOUND_POOL_MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // SoundPool sample IDs. 0 = "not loaded / missing file"; play() then no-ops.
    private var flapId: Int = 0
    private var scoreId: Int = 0
    private var hitId: Int = 0
    private var fallId: Int = 0

    private var musicPlayer: MediaPlayer? = null

    /**
     * Tracks whether the host *wants* music to be playing. Distinct from
     * `musicPlayer.isPlaying`, which is false while the activity is paused
     * even though we want playback to resume on `onResume`.
     */
    private var musicWanted: Boolean = false

    /**
     * Tracks the latest non-ducked target volume so [resumeMusic] can
     * restore the right level (ducked vs. full) after a lifecycle bounce.
     */
    private var musicTargetVolume: Float = GameConfig.MUSIC_VOLUME

    init {
        loadAllSfx()
        prepareMusic()
    }

    // =====================================================================
    //  Loading
    // =====================================================================

    private fun loadAllSfx() {
        flapId = loadSfx(GameConfig.SFX_FLAP_RES)
        scoreId = loadSfx(GameConfig.SFX_SCORE_RES)
        hitId = loadSfx(GameConfig.SFX_HIT_RES)
        fallId = loadSfx(GameConfig.SFX_FALL_RES)
    }

    private fun loadSfx(name: String): Int {
        val resId = resolveRaw(name) ?: return 0
        return try {
            soundPool.load(appContext, resId, 1)
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "SFX '$name' load failed: ${e.message}")
            0
        }
    }

    private fun prepareMusic() {
        val resId = resolveRaw(GameConfig.MUSIC_RES) ?: return
        try {
            musicPlayer = MediaPlayer.create(appContext, resId)?.apply {
                isLooping = true
                setVolume(GameConfig.MUSIC_VOLUME, GameConfig.MUSIC_VOLUME)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Music load failed: ${e.message}")
            musicPlayer = null
        }
    }

    /** @return resource ID, or null if the file isn't present in `res/raw/`. */
    private fun resolveRaw(name: String): Int? {
        @Suppress("DiscouragedApi") // intentional: lazy resolution lets missing assets degrade gracefully
        val id = appContext.resources.getIdentifier(name, "raw", appContext.packageName)
        return if (id == 0) {
            Log.i(TAG, "Audio '$name' not in res/raw — running silent for this cue.")
            null
        } else id
    }

    // =====================================================================
    //  SFX triggers — one-shots, no state
    // =====================================================================

    fun playFlap()  = playSfx(flapId,  GameConfig.SFX_FLAP_VOLUME)
    fun playScore() = playSfx(scoreId, GameConfig.SFX_SCORE_VOLUME)
    fun playHit()   = playSfx(hitId,   GameConfig.SFX_HIT_VOLUME)
    fun playFall()  = playSfx(fallId,  GameConfig.SFX_FALL_VOLUME)

    private fun playSfx(id: Int, volume: Float) {
        if (GameConfig.AUDIO_MUTED || id == 0) return
        try {
            soundPool.play(id, volume, volume, /* priority = */ 1, /* loop = */ 0, /* rate = */ 1f)
        } catch (e: IllegalStateException) {
            // SoundPool released between load and play — possible during
            // very fast Activity tear-down; treat as silent failure.
            Log.w(TAG, "SoundPool.play failed: ${e.message}")
        }
    }

    // =====================================================================
    //  Music — stateful, lifecycle-driven
    // =====================================================================

    /**
     * Begin or resume background music at full volume. Idempotent — safe to
     * call from both Ready-state entry and on game restart.
     */
    fun startMusic() {
        if (GameConfig.AUDIO_MUTED) return
        val player = musicPlayer ?: return
        musicTargetVolume = GameConfig.MUSIC_VOLUME
        musicWanted = true
        try {
            player.setVolume(musicTargetVolume, musicTargetVolume)
            if (!player.isPlaying) player.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startMusic: ${e.message}")
        }
    }

    /** Lower the music to MUSIC_DUCK_VOLUME without stopping playback. */
    fun duckMusic() {
        if (GameConfig.AUDIO_MUTED) return
        val player = musicPlayer ?: return
        musicTargetVolume = GameConfig.MUSIC_DUCK_VOLUME
        try {
            player.setVolume(musicTargetVolume, musicTargetVolume)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "duckMusic: ${e.message}")
        }
    }

    /** Pause for Activity onPause. Preserves [musicWanted] so [resumeMusic] re-starts it. */
    fun pauseMusic() {
        val player = musicPlayer ?: return
        try {
            if (player.isPlaying) player.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pauseMusic: ${e.message}")
        }
    }

    /** Resume after Activity onResume — only if music was active before pause. */
    fun resumeMusic() {
        if (GameConfig.AUDIO_MUTED) return
        if (!musicWanted) return
        val player = musicPlayer ?: return
        try {
            player.setVolume(musicTargetVolume, musicTargetVolume)
            if (!player.isPlaying) player.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "resumeMusic: ${e.message}")
        }
    }

    /** Final teardown — for Activity onDestroy. After this the manager is dead. */
    fun release() {
        musicWanted = false
        try {
            musicPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "release music: ${e.message}")
        }
        musicPlayer = null
        try {
            soundPool.release()
        } catch (e: Exception) {
            Log.w(TAG, "release pool: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "TappyBird/Audio"
    }
}
