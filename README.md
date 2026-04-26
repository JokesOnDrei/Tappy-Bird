# Tappy Bird

A 2D side-scrolling, tap-to-flap game in the style of Flappy Bird, written
in Kotlin on top of raw Android `SurfaceView` + `Canvas`. No external game
assets — everything is drawn procedurally from primitive shapes, so the
project builds with nothing but the stock Android SDK.

---

## Requirements

- Android Studio Iguana (or newer) — the project is configured for AGP 8.13
  and Kotlin 2.0.21.
- Android SDK **36** (compile + target); **minSdk = 33**.
- JDK 17 (bundled with recent Android Studio releases is fine).

## Build & run

```bash
# From the project root:
./gradlew assembleDebug               # build the debug APK
./gradlew installDebug                # install on the connected device
```

…or open `TappyBird/` in Android Studio, let Gradle sync, pick a device or
emulator, and hit **Run**.

The game launches straight into the READY screen — tap anywhere on the
screen to flap.

## Controls

| State       | Tap does                                             |
|-------------|------------------------------------------------------|
| READY       | Starts the game and applies the first flap.         |
| PLAYING     | Applies an **additive** upward impulse to the bird. |
| GAME_OVER   | Restarts (after a short debounce).                  |

## Project layout

```
app/src/main/java/com/dreideas/tappybird/
├── MainActivity.kt           ← hosts GameView, forwards lifecycle
├── GameView.kt               ← SurfaceView + dedicated game-loop thread
├── GameConfig.kt             ← every tunable constant lives here
├── GameState.kt              ← sealed class: Ready / Playing / GameOver
├── HighScoreRepository.kt    ← SharedPreferences wrapper
├── ScreenUtils.kt            ← dp ⇄ px helpers
└── entities/
    ├── Bird.kt               ← physics + rotation
    ├── Pipe.kt               ← one rectangle
    └── PipePair.kt           ← top+bottom rects, scroll, scoring flag
```

## Responsive design

The game is resolution- and aspect-ratio-independent. Three coordinate
systems coexist — understand them before adding any visual element.

| System | Used for | Conversion |
|---|---|---|
| **World units (wu)** | bird, pipes, ground, physics | 1 wu = 1 px on a 1080×1920 reference device; scaled per device at render time |
| **dp** (density-independent px) | UI text (score, banners), touch targets | `dp × density` → px |
| **px** | final draw output | — |

### How the transform works

`GameView.recomputeScaling()` runs on every surface-size change. It picks
a uniform scale so the bird never stretches into an oval, and *extends*
the world dimension that would otherwise be letterboxed:

- **Taller-than-9:16 screens** (19.5:9, 20:9, 21:9) — scale limited by
  width; `worldH` grows above the 1920-wu baseline to fill the screen.
  The player gets a taller playfield on a 21:9 phone.
- **Wider-than-9:16 screens** (tablets in portrait, Pixel Fold) — scale
  limited by height; `worldW` grows above 1080 wu. The background and
  ground fill to the edges; the bird still sits at `worldW * 0.35`.
- **No black bars ever.** The sky is drawn across the full physical
  surface before the world transform is applied.

`render()` applies the transform once via
`canvas.translate(offset) + canvas.scale(scale)`, then world-space draw
calls are expressed in world units. After `canvas.restore()`, UI is
drawn in pixel coordinates with dp-sized text.

### Safe-area handling

Insets from notches, status bars, and gesture bars are collected via
`ViewCompat.setOnApplyWindowInsetsListener`. UI elements clamp their
positions with `safeTop()` / `safeBottom()` helpers so the score never
hides behind a hole-punch camera and "Tap to Restart" never lands on
the gesture bar.

`AndroidManifest.xml` sets
`android:windowLayoutInDisplayCutoutMode="shortEdges"` so the app draws
edge-to-edge behind the cutout; the inset system then offsets UI clear
of it.

### Adding new visual elements — the rules

- **Gameplay objects** (obstacles, power-ups, particles): draw *inside*
  the scaled block in `render()` using world-unit coordinates. They'll
  scale automatically for every device.
- **UI / HUD** (text, buttons, icons): draw *outside* the scaled block
  using `dp()`-converted sizes, and use `safeTop()` / `safeBottom()`
  for positioning.
- **Physics constants** (in `GameConfig.kt`): always in world units per
  second. Never multiply by `density` or `scale`.
- **Bitmap sprites** (when/if you add them): store at XXXHDPI source
  resolution, draw inside the scaled block at the sprite's *world-unit*
  size. Set `paint.isFilterBitmap = true` for bilinear filtering when
  the scale is not 1.0.

### Configuration changes

`AndroidManifest.xml` declares
`android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"`
so the Activity is **not** recreated on fold/unfold or display-cutout
toggles. `MainActivity.onConfigurationChanged` forwards the event to
`GameView.recomputeScaling()` as a safety net in case the surface-size
callback lags behind.

## Architecture notes

- **MVVM-ish separation** — `GameView` is the "ViewModel + View" (it owns
  the world state and the render surface); entity classes are plain
  domain models with no Android dependencies; `HighScoreRepository`
  isolates persistence.
- **Fixed-timestep loop** (`GameThread`) — `update(dt)` is always called
  with `dt = 1/60 s` using an accumulator. Real elapsed time is clamped
  to 50 ms to avoid spiral-of-death catch-up after hitches. The game
  behaves identically at 30, 60, and 120 FPS displays.
- **Input latency** — `onTouchEvent` runs on the UI thread and mutates
  bird velocity under a `@Synchronized` block, so a tap takes effect on
  the very next physics tick (≤ 1 frame).
- **No memory leaks** — the game-loop thread is started from
  `surfaceCreated` and joined from `surfaceDestroyed`; the host Activity
  also forwards `onPause` → `pause()` so the thread is always torn down
  cleanly before the surface goes away.
- **Collision** — AABB-vs-AABB. Bird is a rectangle matching the sprite's
  visible silhouette (`BIRD_COLLISION_HALF_WIDTH` × `BIRD_COLLISION_HALF_HEIGHT`,
  slightly inset so transparent corner cells don't trigger). Previously
  used circle-vs-AABB; switched because the bird sprite is wider than
  tall and a bounding circle over-reached at the corners.

## Tuning guide

All knobs live in `GameConfig.kt`. Common adjustments:

| Want…                                | Change                                                 |
|--------------------------------------|--------------------------------------------------------|
| Easier — bigger gap                  | increase `GAP_HEIGHT` (current 280 wu; every +20 adds ~10% clearance) |
| Harder — narrower gap                | decrease `GAP_HEIGHT`                                  |
| Faster scrolling                     | increase `SCROLL_SPEED`                                |
| Softer / floatier feel               | decrease `GRAVITY`, or make `FLAP_IMPULSE` more negative |
| Heavier / punchier feel              | increase `GRAVITY`                                     |
| Stronger per-tap lift                | make `FLAP_IMPULSE` more negative (e.g. -550 → -600)   |
| Pipes closer together                | decrease `PIPE_SPACING`                                |
| Thicker / chunkier pipes             | increase `PIPE_WIDTH` (widens the collision window)    |
| Taller ground strip                  | increase `GROUND_HEIGHT`                               |
| Less margin at top/bottom for gap    | decrease `PIPE_GAP_MARGIN`                             |

### About the flap behavior

The spec called out two options for `flap()`:

- **Option A** — `velocityY = FLAP_IMPULSE` (overwrite) ← **shipped**
- **Option B** — `velocityY += FLAP_IMPULSE` (additive)

This project ships with **Option A** — the classic Flappy Bird model.
Every tap gives the same predictable lift regardless of how fast the
bird was falling, so recovery from a deep dive is consistent and
skill-driven rather than requiring the player to mash 4–5 taps to
cancel accumulated downward velocity. `FLAP_IMPULSE` is tuned to -550
so rapid taps don't launch the bird into the ceiling.

To switch to Option B (weighty/additive), edit `Bird.flap()`:

```kotlin
fun flap() {
    velocityY += GameConfig.FLAP_IMPULSE
    velocityY = velocityY.coerceIn(GameConfig.MAX_RISE_SPEED, GameConfig.MAX_FALL_SPEED)
}
```

If you switch to Option B, retune `FLAP_IMPULSE` to around -600 so a
single tap while falling still produces meaningful lift.

## Swapping in sprites later

Rendering is isolated to `drawBird`, `drawPipes`, and `drawGround` inside
`GameView.kt`. To replace a procedurally-drawn shape with a bitmap:

1. Drop the PNG into `app/src/main/res/drawable/`.
2. Load it once (e.g. in `surfaceChanged`) with
   `BitmapFactory.decodeResource(resources, R.drawable.bird)`.
3. Replace the `drawCircle(...)` calls in `drawBird` with
   `canvas.drawBitmap(bitmap, srcRect, destRect, null)`.

The entity classes already expose position, rotation, and bounds — no
game-logic changes required.

## Persistence

High scores are stored in `SharedPreferences`:

- file: `game_prefs`
- key:  `flappy_high_score`
- type: `Int`

Clearing app data resets the best score.
