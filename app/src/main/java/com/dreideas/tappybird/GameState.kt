package com.dreideas.tappybird

/**
 * The three discrete states of the game, modelled as a sealed class so
 * the compiler enforces exhaustive `when` branches.
 *
 *   READY    → idle bobbing bird, "Tap to start" overlay
 *   PLAYING  → full physics, scrolling world, collisions active
 *   GAME_OVER → frozen world, bird falls & spins, show score summary
 */
sealed class GameState {
    object Ready : GameState()
    object Playing : GameState()
    object GameOver : GameState()
}
