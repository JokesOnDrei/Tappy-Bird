package com.dreideas.tappybird

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher activity. Shows the splash animation, then (on tap) starts
 * [MainActivity] and finishes itself.
 *
 * Lifecycle mirrors [MainActivity]: the SurfaceView's render thread stops
 * on `onPause` and resumes on `onResume`. There's no audio in the splash
 * so no audio resources to release.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var splashView: SplashView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        splashView = SplashView(this, onLaunchGame = ::launchGame)
        setContentView(splashView)
    }

    override fun onResume() {
        super.onResume()
        splashView.resume()
    }

    override fun onPause() {
        // Stop the loop BEFORE super so the surface still exists while we join.
        splashView.pause()
        super.onPause()
    }

    private fun launchGame() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        @Suppress("DEPRECATION")  // overrideActivityTransition needs API 34
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
