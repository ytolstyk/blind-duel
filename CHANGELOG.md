# Changelog

## [Unreleased]
### Added
- "Leave Practice" button on the Duel screen while in Solo Test Mode, so a practice session can be exited without playing it out.
- Player name entered on the main menu is now persisted (DataStore) and pre-filled on future launches.

### Fixed
- Solo Test Mode (practice) never tracked the practice dummy's health, so landing hits never ended the duel or showed the win screen. `LoopbackGameConnection` now applies damage to a simulated opponent and echoes back `HealthUpdate`s, so 3 hits now resolves a win exactly like a real duel.
- Top-anchored UI (settings icon, headers, health dots, hint chips, etc.) across every screen no longer sits underneath the system status bar; each screen now insets its content with `statusBarsPadding()` while backgrounds stay edge-to-edge.
