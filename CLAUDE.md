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

- `MainMenu` → `MainMenuScreen` — name entry, Create / Join buttons only; no gameplay options live here
- `Create` → `CreateScreen` — host-only match-length picker, generates a 6-char session code, renders a ZXing-generated QR bitmap, advertises via Nearby Connections, waits for a peer; also hosts the debug-only "Solo Test" entry (`BuildConfig.DEBUG` gated) and its weapon/crates options, which abandon the real Nearby hosting in favor of the loopback opponent
- `Join` → `JoinScreen` — CameraX preview + ML Kit barcode scanner; decodes the QR, discovers the matching advertised endpoint, connects
- `Calibration` → `CalibrationScreen` — mutual-ready handshake, then each device runs its own local 3-2-1 "face your opponent" countdown, captures its compass baseline heading, and exchanges it with the peer
- `Duel` → `DuelScreen` — drag-to-aim / release-to-fire, magazine/reload, health, projectile animation, ambient particles
- `Result` → `ResultScreen` — win/lose/draw graphic; mutual-confirm rematch (both must send `RematchRequest`) or quit to `MainMenu`; optional "Watch Replay" if a recording exists for the match just played
- `Replay` → `ReplayScreen` — reached only from `Result`; local-only zoomed-out playback of the recorded duel, no network side effects

### Connection Layer (`connection/`)

- `GameConnection` — interface: `connectionState: StateFlow<ConnectionState>`, `incomingMessages: SharedFlow<GameMessage>`, `startHosting`, `startJoining`, `sendMessage`, `disconnect`. `ConnectionState` is `Disconnected` / `Connecting` / `Connected(endpointId)` / `Reconnecting(sinceMs)` / `Failed(reason)`.
- `NearbyGameConnection` — real transport over Google Nearby Connections (`P2P_POINT_TO_POINT`, BYTES payloads — reliable, ordered delivery). Host validates the joiner's echoed session code in `onConnectionInitiated` before accepting (`SESSION_CODE_DELIMITER = "::"` prefix on the joiner's endpoint name), rejecting mismatches instead of auto-accepting any nearby discoverer. `sendMessage` serializes off the main thread (`Dispatchers.Default`) since `MotionUpdate` broadcasts every ~120ms. On an unexpected drop, `onDisconnected` transitions to `Reconnecting` and `runReconnectLoop` silently retries re-advertising/re-discovering for up to `GameConstants.RECONNECT_GRACE_PERIOD_MS` before falling back to `Disconnected` — see "Reconnect / Connection Quality / Replay" below for the full mechanism and its concurrency model (`stateLock` + `connectionEpoch`, not a coroutine `Mutex`).
- `LoopbackGameConnection` — debug-only simulated opponent for Solo Test Mode; used by `ActiveGameConnection.selectLoopback()`. Also exposes debug-only fault-injection (`simulateDrop()`, `jitterInjectionEnabled`) so the reconnect state machine and connection-quality classifier can be exercised without two physical devices.
- `ActiveGameConnection` — Hilt-injected singleton selector holding both transports; `selectNearby()` / `selectLoopback()` switch which one `.current` returns.
- `GameMessage` — sealed `@Serializable` wire protocol (kotlinx.serialization JSON): `Hello(name)`, `ReadyForDuel`, `CalibrationComplete(baselineHeadingDegrees)`, `MotionUpdate(headingDegrees, stepVectorXMeters, stepVectorYMeters)`, `FireEvent(hit)`, `HealthUpdate(remainingHealth)`, `DodgeEvent`, `RematchRequest`, `Quit`. `FireEvent` deliberately carries no damage amount — the receiver always applies the fixed `GameConstants.HIT_DAMAGE` locally rather than trusting a peer-supplied value.
- `sessionEndedEvents()` — shared extension on `GameConnection` (merges disconnect + `Quit` message) used identically by `CalibrationViewModel`, `DuelViewModel`, and `ResultViewModel` to avoid triplicated disconnect-watching logic. Note `Reconnecting` deliberately does **not** match this — a transient drop keeps the session alive through the grace period instead of ending it immediately.
- `connectionQuality()` — extension on `GameConnection` (alongside `sessionEndedEvents()`/`quit()`) deriving a Good/Fair/Poor `ConnectionQuality` from jitter in `MotionUpdate` inter-arrival timing, merged with a periodic ticker so silence itself (not just a new arrival) can degrade the reading. Shown on `DuelScreen` only — no `MotionUpdate` traffic exists during Calibration to measure.
- `ReplayRecorder` — Hilt singleton recording a duel's local+remote `GameMessage` stream in memory (elapsed-ms timestamps), capped at `GameConstants.REPLAY_MAX_RECORDED_MESSAGES` with a `wasTruncated` flag rather than silently dropping messages past the cap. `GameSession`-style encapsulation (`private set` + named mutators). Recorded only during Duel, not Calibration.
- `SessionCode` — 6-char unambiguous-alphabet code generation; QR payload encode/decode (`decodeQrPayload` wraps `URLDecoder.decode` in `runCatching` to avoid crashing on a malformed/corrupted scanned code).

### Game Rules & Math (`game/`)

- `GameConstants` — single object holding every tunable number (health, magazine/reload, aim/accuracy, projectile speed/size/travel time, character size, sensor broadcast interval, dead-reckoning tuning, draw window, etc.). Change gameplay feel here first.
- `DuelEngine.kt` — pure Kotlin, no Android deps, unit-tested (`app/src/test/`):
  - `AngleMath` — angle normalization/distance helpers
  - `BearingModel` — `computeInitialOpponentPosition`, `stepDelta` (heading → per-step displacement vector), `computeBearingToOpponentOnScreen`, `isAlignedFaceToFace`
  - `HitTest` — `angleFromDrag`, `testHit`
  - `Reload` — manual-timed reload state machine (progress, perfect-zone check, miss-tap penalty)
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

### Magazine / Reload Mechanic

The basic weapon holds `GameConstants.MAGAZINE_SIZE` (3) rounds. There is no per-shot cooldown — firing is gated solely on `roundsRemaining > 0`; the reload is the only pacing break between shots.

- Firing the round that empties the magazine, or tapping the ammo-pip HUD row voluntarily (forfeiting any rounds left), starts a reload: `roundsRemaining` drops to 0 immediately and `DuelViewModel.reloadStartedAtMs` is set.
- `Reload` (in `DuelEngine.kt`) is a pure, explicit-timestamp state machine: `progressFraction`/`isComplete`/`isInPerfectZone` (the 50%-80% window of `RELOAD_DURATION_MS`) / `penalizedStartTime` (a mistimed ring tap pushes the effective start time forward by `RELOAD_MISS_PENALTY_MS`, clamped so it can never exceed "now" — rapid mistimed mashing can only pin progress at 0%, never produce a runaway stall).
- `DuelViewModel.startReloadTicker()` polls `Reload` while a reload is active and refills the magazine once complete (naturally, or via a well-timed tap on `ReloadRing`).
- `DuelUiState.isReloading` is a derived property (`roundsRemaining <= 0`), not stored — `startReload()`/`completeReload()` always mutate `roundsRemaining` in the same call, so it can't desync.

### Reconnect / Connection Quality / Replay

- **Reconnect-on-drop grace period.** `NearbyGameConnection` guards `_connectionState`/`opponentEndpointId`/`pendingAttemptEpoch` with a plain `synchronized` block plus a monotonic `connectionEpoch` counter, deliberately **not** a coroutine `Mutex` — `disconnect()` is a plain non-suspend interface method (`ActiveGameConnection.switchToLoopback` and `CreateViewModel.onCleared` both rely on it never suspending), so making it suspend to acquire a `Mutex` would be a breaking change. Every reconnect attempt captures the epoch in effect when it started; a stale/superseded callback (a late `onConnectionResult` after an explicit `disconnect()`, or after a different attempt already succeeded) is a no-op rather than writing stale state. `onConnectionResult`'s failure branch never writes `Failed` while a resume attempt is in flight (`pendingResumeSignal != null`) — that would trip `sessionEndedEvents()` mid-grace-window, defeating the feature; it just lets `runReconnectLoop` retry or hit its deadline. `DuelViewModel`/`CalibrationViewModel` observe `connectionState` and expose `isReconnecting`/`reconnectingSinceMs`, driving the shared `ui/components/ReconnectingOverlay.kt` (touch-blocking, composed unconditionally — it owns its own "Reconnected" confirmation delay internally via `LaunchedEffect`, so gating it with an `if` at the call site would break that). `DuelViewModel.isDuelPaused()` gates all five input handlers that would otherwise send a message or consume a local resource during the pause (`onAimDrag`, `onAimRelease`, `onManualReloadRequested`, `onReloadRingTapped`, `onDodgeTapped`) — **not** the same gate as `canFire()`, which only covers ammo eligibility. `CalibrationViewModel` additionally has two independently-scoped `GameConstants.CALIBRATION_HANDSHAKE_TIMEOUT_MS` timeout jobs (one for the human-paced `AwaitingBothReady` phase, one for the automated `AwaitingOpponentBaseline` phase) that call the existing `connection.quit()` extension on expiry if a one-shot handshake message was lost during a reconnect gap — reusing `quit()`/`sessionEndedEvents()` rather than introducing a new UI state, so both peers end the session symmetrically. One-shot messages (`FireEvent`/`HealthUpdate`/`RematchRequest`/`DodgeEvent`) lost during a reconnect gap are an accepted, unmitigated limitation — only the handshake case gets a recovery path, since an unmitigated hang there is strictly worse than the pre-feature clean session end.
- **Connection quality indicator.** `game/DuelEngine.kt`'s `ConnectionQualityMath.classify` is a pure function of a list of inter-arrival gaps; `GameConnection.connectionQuality()` merges real `MotionUpdate` arrivals with an independent ticker (interval = `GameConstants.CONNECTION_QUALITY_HARD_TIMEOUT_MS`) so silence itself forces re-classification — a flow built purely from message arrivals can't detect the absence of a message. Shown via `ui/components/ConnectionQualityDot.kt`, isolated into its own nested composable in `DuelScreen.kt` per the recomposition rule below (it can tick as often as ~120ms).
- **Spectator/replay mode.** `ui/replay/ReplayViewModel.kt` reconstructs both players' position/heading/health from `ReplayRecorder`'s snapshot using `DuelEngine`'s `TimeSeriesSampling.sampleAtOrBefore` (hold-last-value, for continuous state, binary-searched against a pre-sorted list — sorted once at construction, not per playback tick) and `TimeSeriesSampling.eventsInWindow` (all events in a tick's window, for discrete `FireEvent`/`HealthUpdate` crossings — needed so a simultaneous-draw double-hit isn't silently collapsed to one). It's a from-scratch reconstruction rather than replaying recorded messages back through `DuelViewModel`'s own live code path, since that ViewModel's local-side state is driven by live sensor flows (not messages) and entangles many side effects (network sends, haptics, sound, `GameSession` mutation) that would all need suppressing. The fixed camera scale is computed only once both the recorded position bounds and the Compose-measured canvas pixel size are known (mirrors the `densityPxPerDp` ViewModel/Compose split in `DuelViewModel`/`DuelScreen`). `playbackPositionMs` is isolated into its own nested `ReplayCanvas` composable in `ui/replay/ReplayScreen.kt`, same recomposition-isolation reasoning as `DuelReloadRing`.
- **Accepted security trade-off (reconnect grace period, found on review):** before this feature, any Nearby Connections drop failed closed. Now `advertisedSessionCode` is re-advertised in cleartext on every drop for up to `RECONNECT_GRACE_PERIOD_MS`, and `onConnectionInitiated` accepts whichever endpoint reconnects first with a matching code — there's no check that it's the *same* endpoint that dropped. An attacker already in range who knows the session code could race to reconnect during that window and get seated as "the opponent" for the rest of the match (a session-takeover/DoS vector against the legitimate player — damage still can't be forged, since `HIT_DAMAGE`/`coerceIn` are unaffected by who's connected). Documented explicitly in `NearbyGameConnection.runReconnectLoop`'s doc comment; a proper fix would need a per-session reconnect token instead of reusing the static QR-scanned code, which is a wire-protocol change out of scope so far.

### Sensors (`sensor/`)

- `MotionProvider` — `headingUpdates(): Flow<HeadingSample>` (rotation-vector sensor via `callbackFlow`), `stepEvents(): Flow<Unit>` (step-detector sensor; flow simply never emits if the sensor is unavailable), `isAccuracyLow(accuracy)`.
- `Haptics` — vibration feedback on taking a hit (`VibratorManager`/`Vibrator`, no pre-API-26 branch needed since minSdk 26 already guarantees `VibrationEffect`).

### Permissions (`permission/GamePermissions.kt`)

API-level-conditional required-permission lists (Nearby/Bluetooth, camera, `ACTIVITY_RECOGNITION`). `ACTIVITY_RECOGNITION` is requested but never a hard blocker — denial just degrades the bearing model (see above), it doesn't block Create/Join.

### UI Components (`ui/components/`)

- `ParticleField` — ambient twinkling-star/dust background, `rememberInfiniteTransition`-driven (no manual polling loop)
- `HealthDots`/`DotsRow`, `AmmoDots` (`LabeledAmmoDots`, tappable for a voluntary reload)
- `ReloadRing` — hand-drawn Canvas ring (track + gold perfect-zone arc + moving dial dot) with its own circular-hit-tested tap gesture, since `CircularProgressIndicator` can't express a moving dial or a colored arc band
- `DismissibleHintChip` — generic dismissible banner; used for both the compass-accuracy warning and the calibration-alignment warning
- `KeepScreenOn` — uses `androidx.activity.compose.LocalActivity` (not a raw `Context` cast) during Calibration + Duel so screen timeout doesn't masquerade as a disconnect
- `ReconnectingOverlay` — full-screen, touch-blocking "Reconnecting… Ns" overlay shown during a reconnect grace period; composed unconditionally on Calibration/Duel (owns its own visibility/confirmation state internally, see "Reconnect / Connection Quality / Replay" above)
- `ConnectionQualityDot` — small Good/Fair/Poor colored dot, Duel-only

### Duel Screen Recomposition Note

`DuelViewModel.reloadProgress` / `isInPerfectReloadZone` update up to 20x/sec while a reload is active and are deliberately kept **out of** `DuelUiState` for that reason. In `DuelScreen.kt`, they're read only inside a nested `DuelReloadRing(viewModel, modifier)` wrapper composable, never as direct arguments in `DuelScreen`'s own body — reading them directly there would tie `DuelScreen`'s whole recompose scope (including its `Canvas`, whose `onDraw` lambda is a plain non-memoized function type) to every reload tick. Preserve this pattern for any other high-frequency state added to this screen.

## Key Files

| File | Role |
|------|------|
| `BlindDuelApp.kt` | `@HiltAndroidApp` Application class |
| `MainActivity.kt` | `@AndroidEntryPoint`, single Activity, Compose NavHost, portrait-locked |
| `navigation/Navigation.kt` | Nav graph (MainMenu, Create, Join, Calibration, Duel, Result, Replay) |
| `connection/GameConnection.kt` | Transport interface + `ConnectionState` + `sessionEndedEvents()` + `connectionQuality()` |
| `connection/NearbyGameConnection.kt` | Real Nearby Connections transport; reconnect-on-drop grace period |
| `connection/LoopbackGameConnection.kt` | Debug-only simulated opponent (Solo Test Mode) + fault injection |
| `connection/ActiveGameConnection.kt` | Selector between Nearby and Loopback transports |
| `connection/GameMessage.kt` | Sealed wire protocol + `toBytes()`/`toGameMessage()` |
| `connection/ReplayRecorder.kt` | In-memory duel `GameMessage` recording for spectator/replay mode |
| `connection/SessionCode.kt` | Session code + QR payload encode/decode |
| `game/GameConstants.kt` | Every tunable gameplay number — start here to retune feel |
| `game/DuelEngine.kt` | Pure rules/math: angles, bearing model, hit test, reload, outcome resolution |
| `game/GameSession.kt` | Cross-screen duel state, encapsulated mutation methods only |
| `sensor/MotionProvider.kt` | Compass heading + step-detector flows |
| `sensor/Haptics.kt` | Hit vibration feedback |
| `permission/GamePermissions.kt` | Required/hard-required permission lists |
| `ui/mainmenu/MainMenuScreen.kt` + `ViewModel` | Name entry, Create/Join |
| `ui/create/CreateScreen.kt` + `ViewModel` | Match-length picker, QR generation + advertising, debug-only Solo Test options |
| `ui/join/JoinScreen.kt` + `ViewModel` | CameraX/ML Kit QR scanning + discovery |
| `ui/calibration/CalibrationScreen.kt` + `ViewModel` | Ready handshake, countdown, baseline exchange (sealed `CalibrationPhase` state machine) |
| `ui/duel/DuelScreen.kt` + `ViewModel` | Aim/fire/magazine/reload/health/projectile animation |
| `ui/result/ResultScreen.kt` + `ViewModel` | Win/lose/draw graphic, mutual-confirm rematch/quit, "Watch Replay" entry point |
| `ui/replay/ReplayScreen.kt` + `ViewModel` | Zoomed-out top-down replay playback of a recorded duel |
| `ui/components/` | `ParticleField`, `HealthDots`/`DotsRow`, `AmmoDots`, `ReloadRing`, `DismissibleHintChip`, `KeepScreenOn`, `ReconnectingOverlay`, `ConnectionQualityDot` |
| `gradle/libs.versions.toml` | All dependency versions (version catalog) |

## Testing

`DuelEngine` (angle math, bearing model incl. the step-detector-unavailable degraded path, hit test, reload, `OutcomeResolver`'s draw-window race, `ConnectionQualityMath`, and `TimeSeriesSampling`) is fully unit tested under `app/src/test/java/com/tolstykh/blindduel/game/DuelEngineTest.kt` — pure Kotlin, no Android/instrumentation needed. `connection/ReplayRecorderTest.kt` covers `ReplayRecorder`'s record/start/stop/snapshot/cap semantics, also pure JVM. `ui/calibration/CalibrationViewModelTest.kt` uses `mockk` + `kotlinx-coroutines-test` (`StandardTestDispatcher`, `Dispatchers.setMain`) to verify the handshake-timeout behavior — note `MutableSharedFlow` has no replay, so a test emitting into a fake `GameConnection.incomingMessages` must let the ViewModel's collector coroutine actually subscribe (`dispatcher.scheduler.runCurrent()`) before emitting, or the value is silently dropped. Run `./gradlew test` before considering a task complete. Add tests for any new logic added to `game/`.

**Not testable in this environment:** Nearby Connections requires two physical devices (doesn't work in the emulator). The QR-pairing → calibration → live-duel path is verified by code review and the Solo Test Mode loopback path, not by an actual two-phone session — treat this as the first thing to manually verify on real hardware after any change to `connection/` or the calibration/duel flow. This applies especially to `NearbyGameConnection`'s reconnect grace period (resume-advertising/resume-discovery behavior, asymmetric-drop timing) — `LoopbackGameConnection`'s `simulateDrop()`/`jitterInjectionEnabled` fault injection covers the `Reconnecting` state machine and quality-classifier branches via Solo Test Mode, but not real Nearby Connections API behavior under a real drop.

## Review Protocol Notes Specific to This Project

- `game/GameConstants.kt` is the single source of truth for tunable numbers — never inline a magic number for health, magazine/reload timing, aim radius, projectile speed/size, character size, sensor tuning, or timing that has an equivalent constant already defined here.
- Never trust a peer-supplied damage/health delta in `connection/GameMessage.kt` handling — always recompute from local `GameConstants.HIT_DAMAGE` and `.coerceIn(0, GameConstants.MAX_PLAYER_HEALTH)`. This was a real security fix (unbounded-damage cheat vector), not a hypothetical.
- Any state that updates faster than ~5Hz inside `DuelScreen` must be isolated into its own nested composable (see "Duel Screen Recomposition Note" above) rather than read directly in `DuelScreen`'s body. This also applies to `ReplayScreen`'s `playbackPositionMs`.
- `GameSession` must stay encapsulated (`private set` + named mutator methods) — do not reintroduce direct field writes from `ui/`. `ReplayRecorder` follows the same discipline.
- `NearbyGameConnection`'s reconnect state (`_connectionState`, `opponentEndpointId`, `pendingAttemptEpoch`) must stay guarded by `stateLock` + `connectionEpoch` on every writer, including `disconnect()` and both branches of `onConnectionResult` — a Mutex/lock is only as strong as its weakest writer. Do not switch this to a coroutine `Mutex`; `disconnect()` must stay non-suspend (see "Reconnect / Connection Quality / Replay" above for why).

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
