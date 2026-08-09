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

- `MainMenu` → `MainMenuScreen` — name entry (persisted via `AppPreferencesRepository`, pre-filled on future launches), Create / Join buttons, and a Settings entry point; no gameplay options live here
- `Settings` → `SettingsScreen` — sound volume, haptic intensity, screen-shake intensity sliders, and Trail theme / Particle theme cosmetic pickers; all persisted via `AppPreferencesRepository` and applied live if changed mid-duel
- `Create` → `CreateScreen` — host-only match-length picker (`MatchLength`: Quick Duel/Standard/Marathon), generates a 6-char session code, renders a ZXing-generated QR bitmap, advertises via Nearby Connections, waits for a peer; also hosts the debug-only "Solo Test" entry (`BuildConfig.DEBUG` gated) and its starting-weapon/crates-toggle options, which abandon the real Nearby hosting in favor of the loopback opponent
- `Join` → `JoinScreen` — CameraX preview + ML Kit barcode scanner; decodes the QR, discovers the matching advertised endpoint, connects
- `Calibration` → `CalibrationScreen` — mutual-ready handshake, then each device runs its own local 3-2-1 "face your opponent" countdown, captures its compass baseline heading, and exchanges it with the peer
- `Duel` → `DuelScreen` — drag-to-aim / release-to-fire, per-weapon magazine/reload, dodge button, health (own + opponent), weapon crates, projectile animation, ambient particles
- `Result` → `ResultScreen` — win/lose/draw graphic; mutual-confirm rematch (both must send `RematchRequest`) or quit to `MainMenu`; optional "Watch Replay" if a recording exists for the match just played
- `Replay` → `ReplayScreen` — reached only from `Result`; local-only zoomed-out playback of the recorded duel, no network side effects

### Connection Layer (`connection/`)

- `GameConnection` — interface: `connectionState: StateFlow<ConnectionState>`, `incomingMessages: SharedFlow<GameMessage>`, `startHosting`, `startJoining`, `sendMessage`, `disconnect`. `ConnectionState` is `Disconnected` / `Connecting` / `Connected(endpointId)` / `Reconnecting(sinceMs)` / `Failed(reason)`.
- `NearbyGameConnection` — real transport over Google Nearby Connections (`P2P_POINT_TO_POINT`, BYTES payloads — reliable, ordered delivery). Host validates the joiner's echoed session code in `onConnectionInitiated` before accepting (`SESSION_CODE_DELIMITER = "::"` prefix on the joiner's endpoint name), rejecting mismatches instead of auto-accepting any nearby discoverer. `sendMessage` serializes off the main thread (`Dispatchers.Default`) since `MotionUpdate` broadcasts every ~120ms. On an unexpected drop, `onDisconnected` transitions to `Reconnecting` and `runReconnectLoop` silently retries re-advertising/re-discovering for up to `GameConstants.RECONNECT_GRACE_PERIOD_MS` before falling back to `Disconnected` — see "Reconnect / Connection Quality / Replay" below for the full mechanism and its concurrency model (`stateLock` + `connectionEpoch`, not a coroutine `Mutex`).
- `LoopbackGameConnection` — debug-only simulated opponent for Solo Test Mode; used by `ActiveGameConnection.selectLoopback()`. Also exposes debug-only fault-injection (`simulateDrop()`, `jitterInjectionEnabled`) so the reconnect state machine and connection-quality classifier can be exercised without two physical devices.
- `ActiveGameConnection` — Hilt-injected singleton selector holding both transports; `selectNearby()` / `selectLoopback()` switch which one `.current` returns.
- `GameMessage` — sealed `@Serializable` wire protocol (kotlinx.serialization JSON): `Hello(name, maxHealth)`, `ReadyForDuel`, `CalibrationComplete(baselineHeadingDegrees)`, `MotionUpdate(headingDegrees, stepVectorXMeters, stepVectorYMeters, sniperAimHeadingDegrees)`, `FireEvent(hit, weaponType, travelDurationMs)`, `HealthUpdate(remainingHealth)`, `DodgeEvent`, `RematchRequest`, `Quit`. `FireEvent` deliberately carries no damage amount — the receiver always applies the fixed `GameConstants.HIT_DAMAGE` locally rather than trusting a peer-supplied value; `weaponType` is trusted only as a cosmetic/lookup key (safe only because every `WeaponConfig` deals identical damage — see the `WeaponConfigs.init` guard below) and `travelDurationMs` is clamped to `MAX_PROJECTILE_TRAVEL_DURATION_MS` before it can feed a tween/delay. `Hello.maxHealth` is the host's chosen `MatchLength`; the joiner adopts it (clamped) rather than picking its own, since there's no server to arbitrate a mismatch. `sniperAimHeadingDegrees` is non-null only while the sender is actively aiming the sniper with rounds available — an absolute (magnetic-frame) heading, not screen-relative, since two independently-oriented devices share no screen-relative frame. `DodgeEvent` is zero-payload and rate-limited on receipt (not just gated on the sender's own cooldown-driven UI) to bound a modified client's dodge spam.
- `sessionEndedEvents()` — shared extension on `GameConnection` (merges disconnect + `Quit` message) used identically by `CalibrationViewModel`, `DuelViewModel`, and `ResultViewModel` to avoid triplicated disconnect-watching logic. Note `Reconnecting` deliberately does **not** match this — a transient drop keeps the session alive through the grace period instead of ending it immediately.
- `connectionQuality()` — extension on `GameConnection` (alongside `sessionEndedEvents()`/`quit()`) deriving a Good/Fair/Poor `ConnectionQuality` from jitter in `MotionUpdate` inter-arrival timing, merged with a periodic ticker so silence itself (not just a new arrival) can degrade the reading. Shown on `DuelScreen` only — no `MotionUpdate` traffic exists during Calibration to measure.
- `ReplayRecorder` — Hilt singleton recording a duel's local+remote `GameMessage` stream in memory (elapsed-ms timestamps), capped at `GameConstants.REPLAY_MAX_RECORDED_MESSAGES` with a `wasTruncated` flag rather than silently dropping messages past the cap. `GameSession`-style encapsulation (`private set` + named mutators). Recorded only during Duel, not Calibration.
- `SessionCode` — 6-char unambiguous-alphabet code generation; QR payload encode/decode (`decodeQrPayload` wraps `URLDecoder.decode` in `runCatching` to avoid crashing on a malformed/corrupted scanned code).

### Game Rules & Math (`game/`)

- `GameConstants` — single object holding every tunable number (health, magazine/reload, per-weapon stats, dodge/fog-of-war, aim/accuracy, projectile speed/size/travel time, crate spawn/fall/destruction, character size, sensor broadcast interval, dead-reckoning tuning, draw window, etc.). Change gameplay feel here first. Also assembles `WeaponConfigs` (one `WeaponConfig` per `WeaponType`, built from the flat per-weapon constants), whose `init` block fails loudly if any weapon's `damage` ever diverges from `HIT_DAMAGE` — see the anti-cheat note above on `FireEvent.weaponType`.
- `DuelEngine.kt` — pure Kotlin, no Android deps, unit-tested (`app/src/test/`):
  - `AngleMath` — angle normalization/distance helpers
  - `BearingModel` — `computeInitialOpponentPosition`, `stepDelta` (heading → per-step displacement vector), `computeBearingToOpponentOnScreen`, `sniperDotOffsetDegrees` (sniper aim-telemetry dot), `isAlignedFaceToFace`
  - `ProximityMath` / `ProximityZone` (`FAR`/`NEAR`/`VERY_NEAR`) — classifies estimated opponent distance for the "getting close" proximity haptic
  - `HitTest` — `angleFromDrag`, `testHit`
  - `WeaponType` (`PISTOL`/`SHOTGUN`/`BOW`/`SNIPER`/`LASER`) + `WeaponConfig` — per-weapon magazine size, reload timing, hit tolerance, damage, loud/silent tell, projectile speed; assembled into `GameConstants.WeaponConfigs`
  - `BowMath` — charge-hold-to-speed curve (`BOW_MIN_CHARGE_HOLD_MS`..pull distance → `BOW_MIN_SPEED_DP_PER_SEC`..`BOW_MAX_SPEED_DP_PER_SEC`)
  - `LaserBeamHitAccumulator` — tracks whether a 1s drag-to-sweep beam has already scored its one allowed hit this activation
  - `FallingCrate` + `CrateMath` — crate fall-position-over-time and `isRayHit` (fixed-radius hit region around the crate's true position, not an angular cone, since crates are visible on screen)
  - `FogOfWarMath` — the longer since the local player's own last detected step, the more their own outgoing-shot hit tolerance widens (proportional to each weapon's own base tolerance, not a flat bonus)
  - `DodgeMath` — narrows the *opponent's* effective hit tolerance against you for `DODGE_WINDOW_MS` after a `DodgeEvent`, gated by `DODGE_COOLDOWN_MS`
  - `HitToleranceMath` — combines a weapon's base tolerance with `FogOfWarMath` (widens the shooter's own tolerance) and `DodgeMath` (narrows the tolerance the opponent gets to use against a dodging player) into the final effective tolerance used by `HitTest`
  - `Reload` — manual-timed reload state machine (progress, perfect-zone check, miss-tap penalty)
  - `DuelOutcome` (`Ongoing` / `Win` / `Loss` / `Draw`) + `OutcomeResolver` — resolves the simultaneous-double-elimination race using `GameConstants.DRAW_WINDOW_MS`
  - `ConnectionQuality` (`GOOD`/`FAIR`/`POOR`) + `ConnectionQualityMath` — classifies jitter in `MotionUpdate` inter-arrival gaps
  - `TimeSeriesSampling` — `sampleAtOrBefore` (binary-searched hold-last-value) + `eventsInWindow`, used by replay reconstruction
- `MatchLength` — pre-duel, host-only enum (`QUICK`/`STANDARD`/`MARATHON` → 2/3/5 hits to win); not itself `@Serializable`, only its raw `maxHealth` `Int` crosses the wire via `GameMessage.Hello`.
- `GameSession` — Hilt singleton holding cross-screen duel state (player names, match max health, calibration baselines, practice-mode starting weapon/crates-toggle, final outcome). All fields are `private set`; mutated only through named methods (`recordLocalPlayerName`, `recordOpponentName`, `recordMatchMaxHealth`, `enterPracticeMode`/`exitPracticeMode`, `recordCalibration`, `recordOutcome`, `resetForNewDuel`) — never assign its fields directly from `ui/`.
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

### Weapons & Crates

Five `WeaponType`s, each a `WeaponConfig` in `GameConstants.WeaponConfigs`: **pistol** (base loadout, 3 rounds), **shotgun** (3 shells, wide hit-tolerance cone modeling spread, slow, loud), **hunting bow** (4 arrows, requires holding past `BOW_MIN_CHARGE_HOLD_MS` before firing — speed scales with pull distance via `BowMath`, silent), **sniper rifle** (2 shots, narrow tolerance, fast, loud, and the only weapon broadcasting live `sniperAimHeadingDegrees` telemetry the opponent can see and react to), **laser** (1 charge, a 1-second drag-to-sweep continuous beam scoring at most one hit per activation via `LaserBeamHitAccumulator`, silent).

- Weapon crates (`FallingCrate`) fall independently on each device — no network sync, capped to one active at a time. A shot destroys a crate only if its aim ray passes within `CRATE_HIT_RADIUS_DP` of the crate's real position (`CrateMath.isRayHit`, a fixed-size hit region, not an angular cone, since a crate is actually visible on screen). Destroying a crate spends a round and grants the new weapon; a crate weapon is never refilled — once its magazine is spent (naturally or via an early manual reload), the player automatically reverts to the pistol.
- "Loud" weapons (pistol, shotgun, sniper) always telegraph an incoming shot to the opponent even on a miss; "silent" weapons (bow, laser) give zero warning unless the shot actually connects.
- `FireEvent.weaponType`/`travelDurationMs` are cosmetic/animation-only on receipt (see the `GameMessage` bullet above for why trusting them is safe) — hit/miss and damage are always resolved locally by the shooter exactly as in the base model.
- Solo Test Mode's starting weapon and crates-enabled toggle are debug-only options on `CreateScreen`, threaded through `GameSession.enterPracticeMode(startingWeapon, cratesEnabled)`; a real (non-practice) duel always starts on the pistol with crates enabled.

### Dodge / Fog-of-War / Hit Tolerance

Two independent modifiers combine (via `HitToleranceMath`) with a weapon's own base `hitToleranceDegrees` to produce the tolerance actually used by `HitTest` for a given shot:

- **Dodge.** Tapping the `DodgeButton` HUD element (bottom-left of Duel) broadcasts a zero-payload `DodgeEvent`, gated by a client-side `DODGE_COOLDOWN_MS` cooldown shown as the button's fill/alpha. For `DODGE_WINDOW_MS` after the opponent's dodge is received, *their* effective hit tolerance against you narrows by `DODGE_TOLERANCE_MULTIPLIER` — rewarding ducking/juking rather than standing still. The receiver also rate-limits incoming `DodgeEvent`s independently of the sender's cooldown, so a modified client can't spam an "always dodging" exploit.
- **Fog-of-war confidence decay.** The longer since the local player's own last detected step, the more forgiving *their own* outgoing-shot hit tolerance becomes (`FogOfWarMath`, ramping from `FOG_OF_WAR_DECAY_START_MS` to `FOG_OF_WAR_DECAY_FULL_MS`, capped at `FOG_OF_WAR_MAX_BONUS_FRACTION` of the weapon's own base tolerance so the bonus scales sanely across a sniper's narrow tolerance and a shotgun's wide one alike). Not shown in the UI — it's a formalization of the existing "your sensor estimate gets worse over time" jankiness, not a new visible mechanic.
- **Proximity haptic.** A related but separate signal: crossing into a `ProximityMath`-classified nearer zone (`PROXIMITY_HAPTIC_NEAR_METERS`/`VERY_NEAR_METERS`) triggers a short haptic pulse (edge-triggered, with a retrigger floor so hovering at a boundary doesn't buzz continuously) — purely informational, does not affect hit tolerance.

### Reconnect / Connection Quality / Replay

- **Reconnect-on-drop grace period.** `NearbyGameConnection` guards `_connectionState`/`opponentEndpointId`/`pendingAttemptEpoch` with a plain `synchronized` block plus a monotonic `connectionEpoch` counter, deliberately **not** a coroutine `Mutex` — `disconnect()` is a plain non-suspend interface method (`ActiveGameConnection.switchToLoopback` and `CreateViewModel.onCleared` both rely on it never suspending), so making it suspend to acquire a `Mutex` would be a breaking change. Every reconnect attempt captures the epoch in effect when it started; a stale/superseded callback (a late `onConnectionResult` after an explicit `disconnect()`, or after a different attempt already succeeded) is a no-op rather than writing stale state. `onConnectionResult`'s failure branch never writes `Failed` while a resume attempt is in flight (`pendingResumeSignal != null`) — that would trip `sessionEndedEvents()` mid-grace-window, defeating the feature; it just lets `runReconnectLoop` retry or hit its deadline. `DuelViewModel`/`CalibrationViewModel` observe `connectionState` and expose `isReconnecting`/`reconnectingSinceMs`, driving the shared `ui/components/ReconnectingOverlay.kt` (touch-blocking, composed unconditionally — it owns its own "Reconnected" confirmation delay internally via `LaunchedEffect`, so gating it with an `if` at the call site would break that). `DuelViewModel.isDuelPaused()` gates all five input handlers that would otherwise send a message or consume a local resource during the pause (`onAimDrag`, `onAimRelease`, `onManualReloadRequested`, `onReloadRingTapped`, `onDodgeTapped`) — **not** the same gate as `canFire()`, which only covers ammo eligibility. `CalibrationViewModel` additionally has two independently-scoped `GameConstants.CALIBRATION_HANDSHAKE_TIMEOUT_MS` timeout jobs (one for the human-paced `AwaitingBothReady` phase, one for the automated `AwaitingOpponentBaseline` phase) that call the existing `connection.quit()` extension on expiry if a one-shot handshake message was lost during a reconnect gap — reusing `quit()`/`sessionEndedEvents()` rather than introducing a new UI state, so both peers end the session symmetrically. One-shot messages (`FireEvent`/`HealthUpdate`/`RematchRequest`/`DodgeEvent`) lost during a reconnect gap are an accepted, unmitigated limitation — only the handshake case gets a recovery path, since an unmitigated hang there is strictly worse than the pre-feature clean session end.
- **Connection quality indicator.** `game/DuelEngine.kt`'s `ConnectionQualityMath.classify` is a pure function of a list of inter-arrival gaps; `GameConnection.connectionQuality()` merges real `MotionUpdate` arrivals with an independent ticker (interval = `GameConstants.CONNECTION_QUALITY_HARD_TIMEOUT_MS`) so silence itself forces re-classification — a flow built purely from message arrivals can't detect the absence of a message. Shown via `ui/components/ConnectionQualityDot.kt`, isolated into its own nested composable in `DuelScreen.kt` per the recomposition rule below (it can tick as often as ~120ms).
- **Spectator/replay mode.** `ui/replay/ReplayViewModel.kt` reconstructs both players' position/heading/health from `ReplayRecorder`'s snapshot using `DuelEngine`'s `TimeSeriesSampling.sampleAtOrBefore` (hold-last-value, for continuous state, binary-searched against a pre-sorted list — sorted once at construction, not per playback tick) and `TimeSeriesSampling.eventsInWindow` (all events in a tick's window, for discrete `FireEvent`/`HealthUpdate` crossings — needed so a simultaneous-draw double-hit isn't silently collapsed to one). It's a from-scratch reconstruction rather than replaying recorded messages back through `DuelViewModel`'s own live code path, since that ViewModel's local-side state is driven by live sensor flows (not messages) and entangles many side effects (network sends, haptics, sound, `GameSession` mutation) that would all need suppressing. The fixed camera scale is computed only once both the recorded position bounds and the Compose-measured canvas pixel size are known (mirrors the `densityPxPerDp` ViewModel/Compose split in `DuelViewModel`/`DuelScreen`). `playbackPositionMs` is isolated into its own nested `ReplayCanvas` composable in `ui/replay/ReplayScreen.kt`, same recomposition-isolation reasoning as `DuelReloadRing`.
- **Accepted security trade-off (reconnect grace period, found on review):** before this feature, any Nearby Connections drop failed closed. Now `advertisedSessionCode` is re-advertised in cleartext on every drop for up to `RECONNECT_GRACE_PERIOD_MS`, and `onConnectionInitiated` accepts whichever endpoint reconnects first with a matching code — there's no check that it's the *same* endpoint that dropped. An attacker already in range who knows the session code could race to reconnect during that window and get seated as "the opponent" for the rest of the match (a session-takeover/DoS vector against the legitimate player — damage still can't be forged, since `HIT_DAMAGE`/`coerceIn` are unaffected by who's connected). Documented explicitly in `NearbyGameConnection.runReconnectLoop`'s doc comment; a proper fix would need a per-session reconnect token instead of reusing the static QR-scanned code, which is a wire-protocol change out of scope so far.

### Sensors (`sensor/`)

- `MotionProvider` — `headingUpdates(): Flow<HeadingSample>` (rotation-vector sensor via `callbackFlow`), `stepEvents(): Flow<Unit>` (step-detector sensor; flow simply never emits if the sensor is unavailable), `tiltUpdates()` (accelerometer-driven, feeds swirl energy into the ambient dust particles when the phone physically moves), `isAccuracyLow(accuracy)`.
- `Haptics` — vibration feedback (`VibratorManager`/`Vibrator`, no pre-API-26 branch needed since minSdk 26 already guarantees `VibrationEffect`): a hit pulse, a short dodge pulse, a short fire pulse, and the proximity "getting close" pulse (see "Dodge / Fog-of-War / Hit Tolerance" above) — all with explicit durations/amplitudes (rather than `VibrationEffect.DEFAULT_AMPLITUDE`) so the runtime haptic-intensity setting can scale them.
- `WeaponSoundPlayer` — placeholder per-weapon fire/hit/miss tones via Android's built-in `ToneGenerator` (no audio asset files exist in the project yet; swap in real SFX under `res/raw` later). Plays only locally on the shooter's own device for feedback/immersion — unrelated to the cross-device loud/silent gameplay tell, which travels via `GameMessage.FireEvent`, not audio. `setVolume()` recreates the native `ToneGenerator` (its volume is fixed at construction) and must be called off the main thread; a `synchronized` lock guards it against a concurrent `playXSound()` call from a gesture callback.

### Settings & Persistence (`data/`)

- `AppPreferencesRepository` — Hilt singleton wrapping a Jetpack DataStore (`Preferences`) with one `Flow`/setter pair per setting: dark mode (defaults `true` — the duel visuals were designed dark-first), player name, sound volume, haptic intensity, shake intensity (all `Int` 0-100, shake up to 200), trail theme, particle theme. Enum-backed settings (`TrailTheme`/`ParticleTheme`) are stored by `.name` and parsed with `runCatching { enumValueOf<...>(it) }`, falling back to the default rather than crashing on a stored value from a renamed/removed enum entry — the same defensive-parsing convention as `SessionCode.decodeQrPayload`.
- `CosmeticThemes.kt` — `TrailTheme` (`VIOLET`/`EMBER`/`MINT`, recolors the player's own outgoing projectile trail) and `ParticleTheme` (`CLASSIC`/`DUSK`/`NEON`, recolors the ambient star/dust backdrop). Deliberately bare identity enums with no Android/Compose types, keeping the data layer framework-free; the actual `Color` mapping lives as an extension property in `ui/theme`.
- `ui/settings/SettingsScreen.kt` + `SettingsViewModel` — sliders/pickers backed 1:1 by `AppPreferencesRepository`; settings apply live if changed mid-duel (`DuelViewModel` collects the same flows).

### Permissions (`permission/GamePermissions.kt`)

API-level-conditional required-permission lists (Nearby/Bluetooth, camera, `ACTIVITY_RECOGNITION`). `ACTIVITY_RECOGNITION` is requested but never a hard blocker — denial just degrades the bearing model (see above), it doesn't block Create/Join.

### UI Components (`ui/components/`)

- `ParticleField` — ambient twinkling-star/dust background, `rememberInfiniteTransition`-driven (no manual polling loop); recolored by the active `ParticleTheme` and pushed around by `MotionProvider.tiltUpdates()`
- `HealthDots`/`DotsRow`, `AmmoDots` (`LabeledAmmoDots`, tappable for a voluntary reload)
- `ReloadRing` — hand-drawn Canvas ring (track + gold perfect-zone arc + moving dial dot) with its own circular-hit-tested tap gesture, since `CircularProgressIndicator` can't express a moving dial or a colored arc band
- `DodgeButton` — HUD element broadcasting `DodgeEvent` on tap; cooldown shown as a simple greyed-out alpha rather than a full progress ring (deliberately simpler than `ReloadRing`). Uses its own `pointerInput`/`detectTapGestures`, same reasoning as `ReloadRing`/`LabeledAmmoDots`, so `DuelScreen`'s full-size drag-to-aim detector never sees a touch that started on the button.
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
| `navigation/Navigation.kt` | Nav graph (MainMenu, Settings, Create, Join, Calibration, Duel, Result, Replay) |
| `connection/GameConnection.kt` | Transport interface + `ConnectionState` + `sessionEndedEvents()` + `connectionQuality()` |
| `connection/NearbyGameConnection.kt` | Real Nearby Connections transport; reconnect-on-drop grace period |
| `connection/LoopbackGameConnection.kt` | Debug-only simulated opponent (Solo Test Mode) + fault injection |
| `connection/ActiveGameConnection.kt` | Selector between Nearby and Loopback transports |
| `connection/GameMessage.kt` | Sealed wire protocol + `toBytes()`/`toGameMessage()` |
| `connection/ReplayRecorder.kt` | In-memory duel `GameMessage` recording for spectator/replay mode |
| `connection/SessionCode.kt` | Session code + QR payload encode/decode |
| `game/GameConstants.kt` | Every tunable gameplay number, incl. `WeaponConfigs` — start here to retune feel |
| `game/DuelEngine.kt` | Pure rules/math: angles, bearing model, weapons, crates, dodge/fog-of-war, hit test, reload, outcome resolution |
| `game/GameSession.kt` | Cross-screen duel state, encapsulated mutation methods only |
| `data/AppPreferencesRepository.kt` | DataStore-backed settings: dark mode, player name, sound/haptic/shake intensity, trail/particle theme |
| `data/CosmeticThemes.kt` | `TrailTheme` / `ParticleTheme` enums |
| `sensor/MotionProvider.kt` | Compass heading + step-detector + tilt flows |
| `sensor/Haptics.kt` | Hit/dodge/fire/proximity vibration feedback |
| `sensor/WeaponSoundPlayer.kt` | Placeholder per-weapon fire/hit/miss tones (`ToneGenerator`) |
| `permission/GamePermissions.kt` | Required/hard-required permission lists |
| `ui/mainmenu/MainMenuScreen.kt` + `ViewModel` | Name entry, Create/Join, Settings entry point |
| `ui/settings/SettingsScreen.kt` + `ViewModel` | Sound/haptic/shake sliders, trail/particle theme pickers |
| `ui/create/CreateScreen.kt` + `ViewModel` | Match-length picker, QR generation + advertising, debug-only Solo Test options (starting weapon, crates toggle) |
| `ui/join/JoinScreen.kt` + `ViewModel` | CameraX/ML Kit QR scanning + discovery |
| `ui/calibration/CalibrationScreen.kt` + `ViewModel` | Ready handshake, countdown, baseline exchange (sealed `CalibrationPhase` state machine) |
| `ui/duel/DuelScreen.kt` + `ViewModel` | Aim/fire/weapons/crates/dodge/magazine/reload/health/projectile animation |
| `ui/result/ResultScreen.kt` + `ViewModel` | Win/lose/draw graphic, mutual-confirm rematch/quit, "Watch Replay" entry point |
| `ui/replay/ReplayScreen.kt` + `ViewModel` | Zoomed-out top-down replay playback of a recorded duel |
| `ui/components/` | `ParticleField`, `HealthDots`/`DotsRow`, `AmmoDots`, `ReloadRing`, `DodgeButton`, `DismissibleHintChip`, `KeepScreenOn`, `ReconnectingOverlay`, `ConnectionQualityDot` |
| `gradle/libs.versions.toml` | All dependency versions (version catalog) |

## Testing

`DuelEngine` — angle math, bearing model (incl. the step-detector-unavailable degraded path and sniper telemetry), hit test, `WeaponConfigs`, `BowMath`, `LaserBeamHitAccumulator`, `CrateMath`, `FogOfWarMath`, `DodgeMath`, `HitToleranceMath`, reload, `OutcomeResolver`'s draw-window race, `ConnectionQualityMath`, and `TimeSeriesSampling` — is fully unit tested under `app/src/test/java/com/tolstykh/blindduel/game/DuelEngineTest.kt` — pure Kotlin, no Android/instrumentation needed. `connection/ReplayRecorderTest.kt` covers `ReplayRecorder`'s record/start/stop/snapshot/cap semantics, also pure JVM. `ui/calibration/CalibrationViewModelTest.kt` uses `mockk` + `kotlinx-coroutines-test` (`StandardTestDispatcher`, `Dispatchers.setMain`) to verify the handshake-timeout behavior — note `MutableSharedFlow` has no replay, so a test emitting into a fake `GameConnection.incomingMessages` must let the ViewModel's collector coroutine actually subscribe (`dispatcher.scheduler.runCurrent()`) before emitting, or the value is silently dropped. Run `./gradlew test` before considering a task complete. Add tests for any new logic added to `game/`.

**Not testable in this environment:** Nearby Connections requires two physical devices (doesn't work in the emulator). The QR-pairing → calibration → live-duel path is verified by code review and the Solo Test Mode loopback path, not by an actual two-phone session — treat this as the first thing to manually verify on real hardware after any change to `connection/` or the calibration/duel flow. This applies especially to `NearbyGameConnection`'s reconnect grace period (resume-advertising/resume-discovery behavior, asymmetric-drop timing) — `LoopbackGameConnection`'s `simulateDrop()`/`jitterInjectionEnabled` fault injection covers the `Reconnecting` state machine and quality-classifier branches via Solo Test Mode, but not real Nearby Connections API behavior under a real drop.

## Review Protocol Notes Specific to This Project

- `game/GameConstants.kt` is the single source of truth for tunable numbers — never inline a magic number for health, magazine/reload timing, aim radius, projectile speed/size, character size, sensor tuning, or timing that has an equivalent constant already defined here.
- Never trust a peer-supplied damage/health delta in `connection/GameMessage.kt` handling — always recompute from local `GameConstants.HIT_DAMAGE` and `.coerceIn(0, GameConstants.MAX_PLAYER_HEALTH)`. This was a real security fix (unbounded-damage cheat vector), not a hypothetical. If a future change ever differentiates per-weapon damage, `FireEvent.weaponType` stops being safe to trust as-is — see `WeaponConfigs.init`'s guard and the `GameMessage` bullet above under "Connection Layer".
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
