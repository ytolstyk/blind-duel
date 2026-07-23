# Feature & Improvement Ideas

A running brainstorm of possible directions for BlindDuel, organized by category. Nothing here is committed — treat it as a menu to pull from, not a roadmap. When something graduates to actual planned work, move it into an issue/plan and note it in `CHANGELOG.md` once shipped.

## Sharing & Social

- **Send session code via contacts/share sheet.** On `CreateScreen`, add a "Share Code" action next to the QR bitmap that opens the Android `Intent.ACTION_SEND` share sheet (text: session code + a short "Join my BlindDuel game" message) so the host can send it through SMS, WhatsApp, etc. without the joiner needing to scan a QR code in person — useful for "meet me at the park" coordination before both players are in proximity yet.
- **Direct contact picker.** A lighter-weight variant: use `Intent.ACTION_PICK` on `ContactsContract.CommonDataKinds.Phone.CONTENT_URI` to pick a single contact and pre-address an SMS with the code, for the common case of "invite this one specific friend."
- **Shareable post-game result card.** On `ResultScreen`, add a "Share result" button that renders the win/lose/draw graphic (already built for the screen) to a bitmap and shares it via `ACTION_SEND` with `EXTRA_STREAM` — bragging rights, no server/backend needed.
- **QR code as an image, not just on-screen.** Let the host save/share the QR bitmap itself (already generated via ZXing) as an image, for posting in a group chat ahead of a meetup.
- **Nearby-friends discovery without QR.** Since Nearby Connections already supports advertising/discovery, add a "Quick Match" mode that skips the QR/session-code flow entirely and just discovers any open BlindDuel host within range — trades the deliberate friend-matching precision of session codes for pure proximity pairing at a meetup/party.

## Gameplay

- **Multiple weapon/projectile types.** `GameConstants` already centralizes projectile speed/size/travel-time — extending `FireEvent`/`GameMessage` with a `weaponType` field could support a slow-but-wide "shotgun spread" vs. a fast narrow "sniper" shot, each with its own accuracy/magazine-size/reload tradeoff, selectable pre-duel.
- **Dodge / juke mechanic.** Since the opponent's position is inferred via dead reckoning (`BearingModel.stepDelta`), a deliberate "duck" or quick sidestep gesture (double-tap, shake) that briefly reduces hit probability would reward real physical movement, reinforcing the "actually move around in the real world" spirit of the game.
- **Two-hit-to-kill vs. current three (or variable health).** Simple `GameConstants.MAX_PLAYER_HEALTH` tuning knob already exists — worth exposing as a pre-duel "match length" option (Quick Duel / Standard / Marathon) rather than a fixed constant.
- **Fog-of-war confidence decay.** Right now the opponent's inferred position only updates from local step/heading data. Adding a "confidence" radius that visibly grows (in code, not shown to the player, but factored into a wider forgiving hit cone) the longer since the last step event would formalize the existing "your sensor estimate gets worse over time" jankiness into an intentional mechanic rather than an emergent one.
- **Environmental/terrain-aware calibration.** Optional short "walk in an L-shape" calibration step (using accelerometer + step count) to improve the initial bearing estimate before the fixed 3-2-1 countdown, for players who want a less chaotic first duel.

## Game Modes

- **Best-of-N match series.** Chain `ResultScreen`'s existing mutual-rematch handshake into an explicit "Game 1 of 3" series with a running score, rather than one-off rematches that reset state each time.
- **Team mode (2v2).** Would require extending the connection layer beyond the current 1:1 Nearby Connections pairing — bigger lift, but the message protocol (`GameMessage`) is already extensible via new sealed subtypes, and `P2P_POINT_TO_POINT` would need to become `P2P_CLUSTER` or a star topology with one device as relay host.
- **Sudden-death / last-stand mode.** After a draw (already handled by `OutcomeResolver`'s `DRAW_WINDOW_MS` race), instead of a full rematch, drop both players to 1 HP for a tiebreaker round.
- **King of the Hill / capture-the-flag-lite.** A stretch mode using the step-vector accumulator (already tracked per-device) to detect "reached a target relative position" rather than eliminating the opponent — shifts the game from combat to a movement-based objective, reusing the same sensor plumbing.
- **Practice/training mode enhancements.** Solo Test Mode's `LoopbackGameConnection` already simulates an opponent; could add difficulty tiers (dummy fires more/less accurately, moves its simulated position) to make solo practice actually useful for improving aim technique.

## Graphics & Audio

- **Sound design.** No audio engine currently exists in the architecture — firing, hit, and miss events are strong candidates for short SFX (bow-twang-style fire sound, impact thud, whiff for miss), plus ambient wind/proximity audio cues tied to the (already-tracked) opponent distance estimate.
- **Haptic patterns beyond hit feedback.** `sensor/Haptics.kt` currently vibrates on taking a hit — could add a distinct, softer pulse pattern for firing, and a "getting close" haptic tied to the estimated opponent distance shrinking, giving a non-visual proximity signal that fits the "never show the opponent" design constraint perfectly.
- **Cosmetic character skins/trails.** Since the opponent's character is deliberately never rendered, cosmetics would only apply to the player's own projectile trail color/style and the hit-flash/explosion burst effect — a low-risk way to add personalization without touching core mechanics.
- **Dynamic background themes.** `ParticleField`'s twinkling-star/dust backdrop (`rememberInfiniteTransition`-driven) could support a few palette themes (day/dusk/neon) as a cosmetic unlock or settings option.
- **Screen-shake/haptic intensity settings.** The recently-added camera shake on fire/hit is a good candidate for a user-adjustable intensity slider for players sensitive to motion effects.

## Networking

- **Reconnect-on-drop grace period.** Nearby Connections drops (walking out of range, phone lock) currently end the session via `sessionEndedEvents()`. A short grace window (e.g., 5–10s) that pauses the duel and attempts silent re-connection before declaring the session ended would help with real-world flakiness (e.g., someone's phone briefly loses Bluetooth).
- **Connection quality indicator.** Surface Nearby Connections' underlying signal strength/bandwidth (where exposed) as a simple bar/dot indicator during `CalibrationScreen`/`DuelScreen`, so players know if jitter in `MotionUpdate` delivery is a connection issue rather than sensor jank.
- **Spectator/replay mode.** Since each device is authoritative only for its own health and the protocol is already a clean sealed message log, recording the `GameMessage` stream during a duel would let a finished match be replayed locally afterward (no rendering of hidden opponent position needed — just show the abstracted health/hit timeline).
- **Wider transport fallback.** Currently Nearby Connections only; a Wi-Fi Direct or local-network (mDNS) fallback path behind the same `GameConnection` interface could help in venues where Bluetooth/Nearby is congested (e.g., crowded parties — the exact context this game is designed for).

## Accessibility & Polish

- **Colorblind-safe hit/health indicators.** `HealthDots` and hit-flash effects should be checked against common colorblindness profiles; consider adding shape or icon differentiation, not just color, for state changes.
- **Reduced-motion setting.** A toggle to tone down or disable camera shake, particle effects, and explosion bursts for players sensitive to visual motion, independent of the cosmetic theme idea above.
- **Audio cues as an accessibility feature, not just flavor.** For players who are hard of hearing or in loud environments, ensure any new audio design (see above) always has a haptic or visual equivalent — never audio-only signaling.
- **Post-round summary stats.** Shots fired, hit rate, average time-to-fire — surfaced on `ResultScreen` using data already available from the existing message log, no new tracking infrastructure required.

## Longer-Shot / Exploratory

- **Local leaderboard (on-device only, no server).** Track win/loss/streak locally via DataStore (already used for the persisted player name) — no backend needed to keep the "no server" architecture intact.
- **Widget or watch companion for session code display.** Lets a host show/share their code without unlocking to the app — speculative, would need its own scoping pass.
- **AR-assisted calibration (stretch).** Using ARCore for a more precise initial heading lock during `CalibrationScreen`, though this cuts against the game's explicit "jankiness is part of the fun" design philosophy and would need to stay optional.

---

*This document is a brainstorm, not a commitment. Cross-check `GameConstants.kt` and `DuelEngine.kt` before scoping any gameplay-affecting idea, since most tunables already exist and just need new UI/config surfaces rather than new engine code.*
