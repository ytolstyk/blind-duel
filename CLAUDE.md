# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (minified + resource shrinking, R8)
./gradlew installDebug           # Build and install on connected device/emulator
./gradlew test                   # Run all unit tests
./gradlew test --tests "com.tolstykh.blindduel.game.DuelEngineTest"  # Run a single test class
./gradlew lint                   # Run static code analysis
./gradlew clean                  # Clean build artifacts
```

## Project Overview

**BlindDuel** is a two-player proximity party game: two phones connect directly to each other (no server) and each player aims blind, using only their phone's compass/step sensors to sense roughly where the other person is standing nearby, then drags-to-aim and releases to fire a projectile. Neither screen ever shows the opponent's character — aiming relies on real-world spatial awareness plus each phone's own imprecise sensor estimate, and that imprecision is intentional ("jankiness is part of the fun").

- **Min SDK:** 26, **Target/Compile SDK:** 36
- **Language:** Kotlin, **UI:** 100% Jetpack Compose + Material 3
- **Architecture:** MVVM with Hilt dependency injection
- **Package:** `com.tolstykh.blindduel`
- Mirrors sibling project `EataBurrita2`'s Gradle/tooling conventions (version catalog, Hilt, type-safe Compose Navigation) where applicable; diverges on minSdk (26 vs 35) and has no shared code/module dependency between the two apps.

The full design rationale (calibration handshake, dead-reckoning bearing model, message protocol, simultaneous-elimination draw window, etc.) is documented in `/Users/yuriytolstykh/.claude/plans/we-re-building-a-multiplayer-humming-ritchie.md`.

## Architecture

### Screens & Navigation

Type-safe Compose Navigation (`navigation/Navigation.kt`) with private `@Serializable object` routes:

- `MainMenu` → `MainMenuScreen` — name entry, Create / Join buttons, debug-only "Solo Test" entry (`BuildConfig.DEBUG` gated)
- `Create` → `CreateScreen` — generates a 6-char session code, renders a ZXing-generated QR bitmap, advertises via Nearby Connections, waits for a peer
- `Join` → `JoinScreen` — CameraX preview + ML Kit barcode scanner; decodes the QR, discovers the matching advertised endpoint, connects
- `Calibration` → `CalibrationScreen` — mutual-ready handshake, then each device runs its own local 3-2-1 "face your opponent" countdown, captures its compass baseline heading, and exchanges it with the peer
- `Duel` → `DuelScreen` — drag-to-aim / release-to-fire, cooldown, health, projectile animation, ambient particles
- `Result` → `ResultScreen` — win/lose/draw graphic; mutual-confirm rematch (both must send `RematchRequest`) or quit to `MainMenu`

### Connection Layer (`connection/`)

- `GameConnection` — interface: `connectionState: StateFlow<ConnectionState>`, `incomingMessages: SharedFlow<GameMessage>`, `startHosting`, `startJoining`, `sendMessage`, `disconnect`. `ConnectionState` is `Disconnected` / `Connecting` / `Connected(endpointId)` / `Failed(reason)`.
- `NearbyGameConnection` — real transport over Google Nearby Connections (`P2P_POINT_TO_POINT`, BYTES payloads — reliable, ordered delivery). Host validates the joiner's echoed session code in `onConnectionInitiated` before accepting (`SESSION_CODE_DELIMITER = "::"` prefix on the joiner's endpoint name), rejecting mismatches instead of auto-accepting any nearby discoverer. `sendMessage` serializes off the main thread (`Dispatchers.Default`) since `MotionUpdate` broadcasts every ~120ms.
- `LoopbackGameConnection` — debug-only simulated opponent for Solo Test Mode; used by `ActiveGameConnection.selectLoopback()`.
- `ActiveGameConnection` — Hilt-injected singleton selector holding both transports; `selectNearby()` / `selectLoopback()` switch which one `.current` returns.
- `GameMessage` — sealed `@Serializable` wire protocol (kotlinx.serialization JSON): `Hello(name)`, `ReadyForDuel`, `CalibrationComplete(baselineHeadingDegrees)`, `MotionUpdate(headingDegrees, stepVectorXMeters, stepVectorYMeters)`, `FireEvent(hit)`, `HealthUpdate(remainingHealth)`, `RematchRequest`, `Quit`. `FireEvent` deliberately carries no damage amount — the receiver always applies the fixed `GameConstants.HIT_DAMAGE` locally rather than trusting a peer-supplied value.
- `sessionEndedEvents()` — shared extension on `GameConnection` (merges disconnect + `Quit` message) used identically by `CalibrationViewModel`, `DuelViewModel`, and `ResultViewModel` to avoid triplicated disconnect-watching logic.
- `SessionCode` — 6-char unambiguous-alphabet code generation; QR payload encode/decode (`decodeQrPayload` wraps `URLDecoder.decode` in `runCatching` to avoid crashing on a malformed/corrupted scanned code).

### Game Rules & Math (`game/`)

- `GameConstants` — single object holding every tunable number (health, cooldown, aim/accuracy, projectile speed/size/travel time, character size, sensor broadcast interval, dead-reckoning tuning, draw window, etc.). Change gameplay feel here first.
- `DuelEngine.kt` — pure Kotlin, no Android deps, unit-tested (`app/src/test/`):
  - `AngleMath` — angle normalization/distance helpers
  - `BearingModel` — `computeInitialOpponentPosition`, `stepDelta` (heading → per-step displacement vector), `computeBearingToOpponentOnScreen`, `isAlignedFaceToFace`
  - `HitTest` — `angleFromDrag`, `testHit`
  - `Cooldown` — fire-cooldown state math
  - `DuelOutcome` (`Ongoing` / `Win` / `Loss` / `Draw`) + `OutcomeResolver` — resolves the simultaneous-double-elimination race using `GameConstants.DRAW_WINDOW_MS`
- `GameSession` — Hilt singleton holding cross-screen duel state (player names, calibration baselines, final outcome). All fields are `private set`; mutated only through named methods (`recordLocalPlayerName`, `recordOpponentName`, `recordCalibration`, `recordOutcome`, `resetForNewDuel`) — never assign its fields directly from `ui/`.
- `Vector2` — minimal `data class(x, y)` with `+`/`-` and `ZERO`.

### Direction / Hit Model (pedestrian-dead-reckoning-lite)

This is the core, deliberately-imprecise mechanic — see the plan doc for full rationale:

1. Calibration: mutual `ReadyForDuel` handshake → independent local 3-2-1 countdown → each device captures its own compass baseline heading and resets its step-vector accumulator to zero → exchanges `CalibrationComplete(baseline)`.
2. Each device assumes the opponent starts `ASSUMED_INITIAL_DISTANCE_METERS` away along its own baseline heading.
3. `TYPE_STEP_DETECTOR` events nudge the local step-vector accumulator (via `BearingModel.stepDelta`) using *current* heading, not the baseline — deliberately rough. No step sensor / denied `ACTIVITY_RECOGNITION` → step vector stays `(0,0)`, degrading gracefully to a heading-only fixed-position model.
4. Both devices broadcast `MotionUpdate` every `HEADING_BROADCAST_INTERVAL_MS`.
5. On fire, the shooter locally computes hit/miss via `BearingModel.computeBearingToOpponentOnScreen` + `HitTest.testHit` and unilaterally decides the outcome (no server arbitration — an accepted 2-peer trust trade-off).
6. Each device is authoritative only for its own health; broadcasts `HealthUpdate` whenever it changes. "I win" is only declared from the opponent's last-received `HealthUpdate` hitting 0, never from local-only state — this is what lets both sides agree on the outcome.

### Sensors (`sensor/`)

- `MotionProvider` — `headingUpdates(): Flow<HeadingSample>` (rotation-vector sensor via `callbackFlow`), `stepEvents(): Flow<Unit>` (step-detector sensor; flow simply never emits if the sensor is unavailable), `isAccuracyLow(accuracy)`.
- `Haptics` — vibration feedback on taking a hit (`VibratorManager`/`Vibrator`, no pre-API-26 branch needed since minSdk 26 already guarantees `VibrationEffect`).

### Permissions (`permission/GamePermissions.kt`)

API-level-conditional required-permission lists (Nearby/Bluetooth, camera, `ACTIVITY_RECOGNITION`). `ACTIVITY_RECOGNITION` is requested but never a hard blocker — denial just degrades the bearing model (see above), it doesn't block Create/Join.

### UI Components (`ui/components/`)

- `ParticleField` — ambient twinkling-star/dust background, `rememberInfiniteTransition`-driven (no manual polling loop)
- `HealthDots`, `CooldownRing` (`CircularProgressIndicator`, lambda `progress = { }`)
- `DismissibleHintChip` — generic dismissible banner; used for both the compass-accuracy warning and the calibration-alignment warning
- `KeepScreenOn` — uses `androidx.activity.compose.LocalActivity` (not a raw `Context` cast) during Calibration + Duel so screen timeout doesn't masquerade as a disconnect

### Duel Screen Recomposition Note

`DuelViewModel.cooldownSecondsRemaining` / `cooldownProgress` update up to 20x/sec while a shot is on cooldown and are deliberately kept **out of** `DuelUiState` for that reason. In `DuelScreen.kt`, they're read only inside a nested `DuelCooldownRing(viewModel, modifier)` wrapper composable, never as direct arguments in `DuelScreen`'s own body — reading them directly there would tie `DuelScreen`'s whole recompose scope (including its `Canvas`, whose `onDraw` lambda is a plain non-memoized function type) to every cooldown tick. Preserve this pattern for any other high-frequency state added to this screen.

## Key Files

| File | Role |
|------|------|
| `BlindDuelApp.kt` | `@HiltAndroidApp` Application class |
| `MainActivity.kt` | `@AndroidEntryPoint`, single Activity, Compose NavHost, portrait-locked |
| `navigation/Navigation.kt` | Nav graph (MainMenu, Create, Join, Calibration, Duel, Result) |
| `connection/GameConnection.kt` | Transport interface + `ConnectionState` + `sessionEndedEvents()` |
| `connection/NearbyGameConnection.kt` | Real Nearby Connections transport |
| `connection/LoopbackGameConnection.kt` | Debug-only simulated opponent (Solo Test Mode) |
| `connection/ActiveGameConnection.kt` | Selector between Nearby and Loopback transports |
| `connection/GameMessage.kt` | Sealed wire protocol + `toBytes()`/`toGameMessage()` |
| `connection/SessionCode.kt` | Session code + QR payload encode/decode |
| `game/GameConstants.kt` | Every tunable gameplay number — start here to retune feel |
| `game/DuelEngine.kt` | Pure rules/math: angles, bearing model, hit test, cooldown, outcome resolution |
| `game/GameSession.kt` | Cross-screen duel state, encapsulated mutation methods only |
| `sensor/MotionProvider.kt` | Compass heading + step-detector flows |
| `sensor/Haptics.kt` | Hit vibration feedback |
| `permission/GamePermissions.kt` | Required/hard-required permission lists |
| `ui/mainmenu/MainMenuScreen.kt` + `ViewModel` | Name entry, Create/Join/Solo Test |
| `ui/create/CreateScreen.kt` + `ViewModel` | QR generation + advertising |
| `ui/join/JoinScreen.kt` + `ViewModel` | CameraX/ML Kit QR scanning + discovery |
| `ui/calibration/CalibrationScreen.kt` + `ViewModel` | Ready handshake, countdown, baseline exchange (sealed `CalibrationPhase` state machine) |
| `ui/duel/DuelScreen.kt` + `ViewModel` | Aim/fire/cooldown/health/projectile animation |
| `ui/result/ResultScreen.kt` + `ViewModel` | Win/lose/draw graphic, mutual-confirm rematch/quit |
| `ui/components/` | `ParticleField`, `HealthDots`, `CooldownRing`, `DismissibleHintChip`, `KeepScreenOn` |
| `gradle/libs.versions.toml` | All dependency versions (version catalog) |

## Testing

`DuelEngine` (angle math, bearing model incl. the step-detector-unavailable degraded path, hit test, cooldown, and `OutcomeResolver`'s draw-window race) is fully unit tested under `app/src/test/java/com/tolstykh/blindduel/game/DuelEngineTest.kt` — pure Kotlin, no Android/instrumentation needed. Run `./gradlew test` before considering a task complete. Add tests for any new logic added to `game/`.

**Not testable in this environment:** Nearby Connections requires two physical devices (doesn't work in the emulator). The QR-pairing → calibration → live-duel path is verified by code review and the Solo Test Mode loopback path, not by an actual two-phone session — treat this as the first thing to manually verify on real hardware after any change to `connection/` or the calibration/duel flow.

## Review Protocol Notes Specific to This Project

- `game/GameConstants.kt` is the single source of truth for tunable numbers — never inline a magic number for health, cooldown, aim radius, projectile speed/size, character size, sensor tuning, or timing that has an equivalent constant already defined here.
- Never trust a peer-supplied damage/health delta in `connection/GameMessage.kt` handling — always recompute from local `GameConstants.HIT_DAMAGE` and `.coerceIn(0, GameConstants.MAX_PLAYER_HEALTH)`. This was a real security fix (unbounded-damage cheat vector), not a hypothetical.
- Any state that updates faster than ~5Hz inside `DuelScreen` must be isolated into its own nested composable (see "Duel Screen Recomposition Note" above) rather than read directly in `DuelScreen`'s body.
- `GameSession` must stay encapsulated (`private set` + named mutator methods) — do not reintroduce direct field writes from `ui/`.

## Changelog

Maintain `CHANGELOG.md` at the project root. Add an entry for every feature added or bug fixed, under an `[Unreleased]` section using this format:

```
## [Unreleased]
### Added
- Description of new feature

### Fixed
- Description of bug fix
```

## Privacy Policy / EULA

The Privacy Policy and EULA for this app are hosted in the sibling `eataburrita-site` repo (`/Users/yuriytolstykh/code/eataburrita-site`), at `src/BlindDuelPrivacy.tsx` (`/blind-duel/privacy`) and `src/BlindDuelEula.tsx` (`/blind-duel/eula`), routed in `src/App.tsx`. Whenever a change here substantially alters what those pages describe — permissions requested (`AndroidManifest.xml`, `permission/GamePermissions.kt`), data collected/transmitted, the peer-to-peer/no-server connectivity model, or third-party SDKs used (Nearby Connections, ML Kit, etc.) — update those two files in `eataburrita-site` to match and bump their "effective as of" date, even if not explicitly asked.
