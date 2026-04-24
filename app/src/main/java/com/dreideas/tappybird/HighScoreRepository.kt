package com.dreideas.tappybird

import android.content.Context
import androidx.core.content.edit

/**
 * Thin wrapper over SharedPreferences for reading / writing the all-time
 * best score. Isolated as a repository so swapping the backing store
 * (e.g. to DataStore, Room, or a cloud service) later touches one class.
 */
class HighScoreRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        GameConfig.PREFS_FILE,
        Context.MODE_PRIVATE
    )

    fun getHighScore(): Int = prefs.getInt(GameConfig.KEY_HIGH_SCORE, 0)

    /**
     * Persist a new high score. Returns true iff [score] beat the previous
     * record and was written, so callers can drive a "NEW HIGH SCORE!" UI.
     */
    fun trySetHighScore(score: Int): Boolean {
        val current = getHighScore()
        if (score <= current) return false
        prefs.edit { putInt(GameConfig.KEY_HIGH_SCORE, score) }
        return true
    }
}
