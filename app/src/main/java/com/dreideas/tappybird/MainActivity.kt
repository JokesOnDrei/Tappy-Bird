package com.dreideas.tappybird

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts the [GameView] as the entire content of the screen.
 *
 * Responsibilities:
 *  - Keep the screen on while playing.
 *  - Forward Activity pause/resume to the game loop so the SurfaceView
 *    thread is torn down cleanly (no leaked GL contexts / canvas locks).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A flapping bird is the worst candidate for a screen timeout.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
    }

    override fun onPause() {
        // Stop the loop BEFORE super so the surface still exists while we join.
        gameView.pause()
        super.onPause()
    }
}
