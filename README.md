# Solitaire

A fast, one-handed Klondike Solitaire for Android. It's written in Kotlin with no third-party
dependencies: the whole table is drawn on a single hardware-accelerated canvas from pre-rendered card
bitmaps, so it starts instantly and animates at full frame rate.

## Features

- **Draw 1 and Draw 3**: pick the mode from the **New** sheet or tap the mode chip. Unlimited passes, with standard scoring.
- **Unlimited undo**: tap **Undo**, or hold it to rewind quickly.
- **Tap to move**: tap a card and it goes to the best legal spot. Tap it again to cycle through the other spots. You can also drag.
- **Hints**: the lightbulb in the top bar highlights the next useful move. Tap it repeatedly to cycle through suggestions.
- **Auto-play** (off by default; turn it on in Settings): safe cards go to the foundations on their own. One undo reverts your move together with any auto-plays it triggered.
- **Auto-finish**: an **Auto-finish** button appears once the game is certain to be won.
- **One-handed layout**: all controls are in a bottom bar, with Undo under your thumb. On tall phones the board sits lower so you can reach it. The **Right hand / Left hand** button in the toolbar mirrors the stock and the toolbar for either thumb.
- **Sound on/off**: use the speaker button in the top bar or the switch in Settings. Sound effects are generated in memory at startup and preloaded into low-latency `AudioTrack`s, so the repo has no audio files. The phone's volume keys control game volume while the app is open.
- **Haptics** (can be turned off), **four-colour suits**, and an option to hide the timer and score.
- **Resume**: the game in progress, its undo history and the clock are saved automatically.
- **Statistics** per mode: games played and won, win rate, current and best streak, best time and high score.
- Deal animation, card-flip animation, a shake when a move isn't allowed, and a bouncing-cards win celebration.

## Building

Open the project in Android Studio (Ladybug or newer), or run:

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # rules-engine unit tests
```

CI (`.github/workflows/android.yml`) runs the tests and uploads debug and release APKs as build artifacts.
The release build is minified and signed with the debug key so you can install it directly. Set up a
real signing config before publishing it.

## Code layout

- `game/`: the pure-Kotlin rules engine (`Klondike`): dealing, move rules, scoring, undo, hints, the safe auto-play rule, auto-complete and saving. It has no Android dependencies and is unit-tested.
- `GameView`: rendering, sprite animation, touch handling (tap and drag), hint highlights and the win cascade.
- `FaceArt`: the jack, queen and king portraits, drawn as vector paths.
- `CardRenderer`: draws card faces, backs and slots into bitmaps once per card size. Suits are vector paths, so they look the same on every device.
- `Sounds`: generates the sound effects and plays them through preloaded static `AudioTrack`s.
- `MainActivity`: the toolbar, bottom sheets, settings, statistics, the timer and saving.
