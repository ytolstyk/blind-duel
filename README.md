# BlindDuel

A two-player proximity party game: two phones connect directly to each other (no server) and each player aims blind, using only their phone's compass and step sensors to sense roughly where the other person is standing nearby, then drags-to-aim and releases to fire a projectile.

Neither screen ever shows the opponent's character — aiming relies on real-world spatial awareness plus each phone's own imprecise sensor estimate, and that imprecision is intentional. Jankiness is part of the fun.

## How it works

1. **Create / Join** — one player creates a session (generates a 6-char code + QR); the other scans the QR to connect over Google Nearby Connections. No internet, no server, no account.
2. **Calibration** — both players face each other, each phone runs its own 3-2-1 countdown and captures its compass baseline heading.
3. **Duel** — drag to aim, release to fire. Each phone tracks the opponent's rough position via dead reckoning (compass heading + step detection) rather than any shared ground truth, so aim is intentionally imprecise.
4. **Result** — first to reduce the opponent's health to zero wins (with a simultaneous-elimination draw window); rematch or return to the menu.

## Tech stack

- **Language:** Kotlin
- **UI:** 100% Jetpack Compose + Material 3
- **Architecture:** MVVM with Hilt dependency injection
- **Connectivity:** Google Nearby Connections (peer-to-peer, no server)
- **Scanning:** CameraX + ML Kit barcode scanning, ZXing for QR generation
- **Min SDK:** 26 · **Target/Compile SDK:** 36

## Building

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (minified + resource shrinking, R8)
./gradlew installDebug           # Build and install on connected device/emulator
./gradlew test                   # Run all unit tests
./gradlew lint                   # Run static code analysis
```

Requires two physical devices to play a real match — Nearby Connections doesn't work in the emulator. A debug-only "Solo Test Mode" is available for practicing against a simulated local opponent without a second device.

## Project structure

See [CLAUDE.md](CLAUDE.md) for a full architecture breakdown (screens/navigation, connection layer, game rules/math, sensors, key files table) and the design rationale doc it references.

## Testing

Core game logic (angle math, bearing model, hit test, cooldown, outcome resolution) is pure Kotlin and fully unit tested under `app/src/test/java/com/tolstykh/blindduel/game/DuelEngineTest.kt`:

```bash
./gradlew test
./gradlew test --tests "com.tolstykh.blindduel.game.DuelEngineTest"
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
