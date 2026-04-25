package com.dreideas.tappybird

import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Hosts [GameView] as the entire content of the screen.
 *
 *  - Keeps the screen awake while playing.
 *  - Forwards Activity pause/resume to the game loop for clean thread teardown.
 *  - On config changes (foldable fold/unfold, display cutout mode changes, etc.)
 *    the manifest prevents Activity recreation; we forward them to GameView so
 *    the world → screen transform is recomputed against the new surface.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The surface will resize and trigger surfaceChanged on its own, but
        // we call this explicitly so the transform is correct even if the
        // callback order changes across OEMs.
        gameView.recomputeScaling()
    }
}
