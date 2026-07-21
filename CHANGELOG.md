# Changelog

## [Unreleased]
### Added
- "Leave Practice" button on the Duel screen while in Solo Test Mode, so a practice session can be exited without playing it out.
- Player name entered on the main menu is now persisted (DataStore) and pre-filled on future launches.
- Reworked the Duel screen's ambient particle backdrop into twinkling parallax stars plus falling dust that swirls when the phone physically moves (new accelerometer-driven `MotionProvider.tiltUpdates()`).
- Projectiles now draw a wobbling flame-style tail, push nearby dust particles away as they travel, and trigger a camera shake on firing and on taking a hit.
- Added an incoming-projectile visual for shots fired at you (flies in from your own live bearing estimate to the opponent; misses now visibly fly past and off-screen instead of only showing a flash).
- Solo Test Mode now periodically simulates an unprompted opponent shot (occasionally a hit) so the incoming-projectile and hit-flash visuals can be previewed without needing to fire first.

### Fixed
- Solo Test Mode (practice) never tracked the practice dummy's health, so landing hits never ended the duel or showed the win screen. `LoopbackGameConnection` now applies damage to a simulated opponent and echoes back `HealthUpdate`s, so 3 hits now resolves a win exactly like a real duel.
- Top-anchored UI (settings icon, headers, health dots, hint chips, etc.) across every screen no longer sits underneath the system status bar; each screen now insets its content with `statusBarsPadding()` while backgrounds stay edge-to-edge.
