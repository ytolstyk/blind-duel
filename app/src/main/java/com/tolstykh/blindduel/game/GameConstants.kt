package com.tolstykh.blindduel.game

/**
 * Every tunable number for the duel mechanic lives here so gameplay feel can be
 * retuned in one place without hunting through screens/view models.
 */
object GameConstants {
    // Health / combat
    const val MAX_PLAYER_HEALTH = 3
    const val HIT_DAMAGE = 1

    // Magazine / reload — three shots per magazine, then a long reload. This is the only pacing
    // lever between shots now: firing has no per-shot cooldown of its own, only the magazine
    // running dry (or a voluntary early reload) creates a break in the action.
    const val MAGAZINE_SIZE = 3
    const val RELOAD_DURATION_MS = 3500L
    const val RELOAD_RING_POLL_INTERVAL_MS = 50L
    const val RELOAD_TICKER_IDLE_POLL_INTERVAL_MS = 250L
    // The "perfect zone" — tapping the reload ring while its dial is between these two fractions
    // of RELOAD_DURATION_MS completes the reload instantly instead of waiting it out.
    const val RELOAD_PERFECT_ZONE_START_FRACTION = 0.5f
    const val RELOAD_PERFECT_ZONE_END_FRACTION = 0.8f
    // A mistimed tap (outside the perfect zone) extends the remaining reload instead of being a
    // free no-op, so spamming taps is never a winning strategy — see Reload.penalizedStartTime.
    const val RELOAD_MISS_PENALTY_MS = 600L
    const val RELOAD_MISS_FLASH_DURATION_MS = 200L
    const val RELOAD_COMPLETE_FLASH_DURATION_MS = 700L
    const val RELOAD_COMPLETE_FADE_IN_MS = 150
    const val RELOAD_COMPLETE_FADE_OUT_MS = 300
    const val RELOAD_RING_SIZE_DP = 56f
    const val RELOAD_RING_STROKE_WIDTH_DP = 6f
    const val RELOAD_RING_DIAL_RADIUS_DP = 5f

    // Aiming / accuracy
    const val AIM_MAX_DRAG_RADIUS_DP = 140f
    const val HIT_ANGLE_TOLERANCE_DEGREES = 20f
    const val AIM_LINE_STROKE_WIDTH_DP = 4f

    // Projectile — PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS is a flat constant, not derived
    // from any measured real-world distance (no GPS/UWB available); it's the delay
    // before a fired shot's FireEvent is actually sent, standing in for flight time.
    const val PROJECTILE_SPEED_DP_PER_SEC = 1200f
    const val PROJECTILE_SIZE_DP = 14f
    const val PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS = 450L
    // Comfortably exceeds any phone diagonal in dp, so the on-screen projectile always
    // visibly exits the canvas bounds before its travel animation finishes.
    const val PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP = 500f
    // Derived (not independently tunable) — how long the visual travel animation takes to
    // cross PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP at PROJECTILE_SPEED_DP_PER_SEC. Shared by
    // the shooter's own outgoing-shot animation and the incoming-shot arrival timing so the
    // receiver's damage flash lines up with the projectile visually reaching the character.
    val PROJECTILE_TRAVEL_DURATION_MS: Long =
        (PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP / PROJECTILE_SPEED_DP_PER_SEC * 1000).toLong()

    // Projectile flame tail
    const val PROJECTILE_TAIL_SEGMENT_COUNT = 6
    const val PROJECTILE_TAIL_SPACING_DP = 16f
    const val PROJECTILE_TAIL_WOBBLE_AMPLITUDE_DP = 6f
    const val PROJECTILE_TAIL_WOBBLE_FREQUENCY = 10f
    // Per-segment shape falloff toward the tail's far end (fraction = segment index / count).
    const val PROJECTILE_TAIL_FADE_FACTOR = 0.85f
    const val PROJECTILE_TAIL_SHRINK_FACTOR = 0.55f

    // Incoming (opponent's) shot — flies in from the receiver's own bearing estimate to the
    // opponent; a miss overshoots past the character and off the far side instead of stopping.
    const val INCOMING_MISS_OVERSHOOT_MULTIPLIER = 1.6f

    // Projectile particle interaction — dust within this radius of a traveling projectile
    // gets pushed radially away from it.
    const val PROJECTILE_PUSH_RADIUS_PX = 90f
    const val PROJECTILE_PUSH_STRENGTH_PX = 46f

    // Character
    const val CHARACTER_SIZE_DP = 72f

    // Damage feedback
    const val DAMAGE_FLASH_DURATION_MS = 300L
    const val DAMAGE_FLASH_PEAK_ALPHA = 0.35f
    const val DAMAGE_OVERLAY_ALPHA_MULTIPLIER = 0.4f

    // Impact explosion drawn on the character when an incoming shot lands — starts in lockstep
    // with lastHitAtMs (itself delayed to match PROJECTILE_TRAVEL_DURATION_MS), so it appears
    // exactly as the incoming projectile's travel animation reaches distance 0.
    // Invariant: (EXPLOSION_RING_COUNT - 1) * EXPLOSION_RING_STAGGER must stay below 1 — each
    // ring's progress is normalized by (1 - its stagger delay), so a stagger large enough to
    // push a ring's delay to/past 1 would divide by zero or go negative.
    const val EXPLOSION_DURATION_MS = 450
    const val EXPLOSION_MAX_RADIUS_DP = 110f
    const val EXPLOSION_RING_COUNT = 3
    const val EXPLOSION_RING_STAGGER = 0.25f
    const val EXPLOSION_RING_STROKE_WIDTH_DP = 4f
    const val EXPLOSION_CORE_ALPHA_MULTIPLIER = 0.5f
    const val EXPLOSION_RING_ALPHA_MULTIPLIER = 0.8f

    // Hit indicator — confirms a landed shot on the opponent, whose character is never
    // rendered on screen, so this is the only feedback that a fired shot actually hit.
    const val HIT_INDICATOR_DURATION_MS = 700L
    const val HIT_INDICATOR_VERTICAL_OFFSET_DP = -120f

    // Camera shake — triggered on firing (small) and on taking a hit (larger, and at a
    // slightly different cycle count so the two never look identical if they overlap).
    const val CAMERA_SHAKE_DURATION_MS = 260
    const val CAMERA_SHAKE_FIRE_MAGNITUDE_DP = 5f
    const val CAMERA_SHAKE_HIT_MAGNITUDE_DP = 10f
    const val CAMERA_SHAKE_FIRE_CYCLES_X = 3.5f
    const val CAMERA_SHAKE_FIRE_CYCLES_Y = 4.5f
    const val CAMERA_SHAKE_HIT_CYCLES_X = 4.55f
    const val CAMERA_SHAKE_HIT_CYCLES_Y = 6.3f

    // Sensors / networking
    const val HEADING_BROADCAST_INTERVAL_MS = 120L
    const val CALIBRATION_COUNTDOWN_SECONDS = 3
    const val CALIBRATION_COUNTDOWN_TICK_MS = 1000L

    // Dead-reckoning tuning
    const val STEP_LENGTH_METERS = 0.7f
    const val ASSUMED_INITIAL_DISTANCE_METERS = 3.0f
    const val FACE_TO_FACE_TOLERANCE_DEGREES = 45f

    // Simultaneous-elimination grace window
    const val DRAW_WINDOW_MS = 700L

    // Ambient particle backdrop — distant twinkling stars (parallax + slow autonomous drift)
    // plus falling dust (loops downward, swirls when the phone is moved).
    const val AMBIENT_STAR_COUNT = 46
    const val AMBIENT_DUST_COUNT = 26
    const val STAR_TWINKLE_PHASE_DURATION_MS = 20_000
    const val STAR_PARALLAX_FACTOR_PX = 26f
    const val STAR_AUTO_DRIFT_PX = 10f
    const val STAR_AUTO_DRIFT_FREQUENCY = 0.3f
    // Twinkle alpha shape: base level, how much the twinkle sine adds on top, and a floor
    // so a star never fully disappears at the bottom of its cycle.
    const val STAR_ALPHA_BASE = 0.25f
    const val STAR_ALPHA_RANGE = 0.6f
    const val STAR_ALPHA_MIN = 0.1f
    const val DUST_FALL_CYCLE_MS = 9_000
    const val DUST_SWIRL_AMPLITUDE_PX = 34f
    const val DUST_SWIRL_WOBBLE_CYCLES = 2.5f
    // A tilt/motion "jolt" feeds swirl energy, which then decays a fixed fraction per
    // accelerometer sample rather than tracking wall-clock time — samples arrive at a
    // roughly steady rate, so this is simpler than delta-time decay and looks the same.
    const val DUST_SWIRL_GAIN = 3.2f
    const val DUST_SWIRL_DECAY_PER_SAMPLE = 0.85f

    // Practice/dev mode only (see LoopbackGameConnection) — periodically simulates an
    // opponent's shot so the incoming-projectile and hit-flash visuals can be previewed
    // without waiting on a real two-phone session.
    const val PRACTICE_AMBIENT_FIRE_MIN_INTERVAL_MS = 4_000L
    const val PRACTICE_AMBIENT_FIRE_MAX_INTERVAL_MS = 9_000L
    const val PRACTICE_AMBIENT_FIRE_HIT_PROBABILITY = 0.3f

    // Practice/dev mode only — the pre-existing simulated dummy's ambient idle motion and its
    // chance of firing back right after you fire (see LoopbackGameConnection.maybeFireBack).
    const val PRACTICE_HEADING_JITTER_DEGREES = 4f
    const val PRACTICE_STEP_TRIGGER_PROBABILITY = 0.03f
    const val PRACTICE_FIRE_BACK_PROBABILITY = 0.35f
    const val PRACTICE_FIRE_BACK_HIT_PROBABILITY = 0.5f

    // Connection
    const val SESSION_CODE_LENGTH = 6
    const val NEARBY_SERVICE_ID = "com.tolstykh.blindduel.SERVICE"

    // Shotgun — wide spread (modeled purely as a widened hit tolerance, hit/miss stays
    // binary), slow projectile, loud. Pellet trail constants are cosmetic only, never
    // consulted by hit-testing.
    const val SHOTGUN_MAGAZINE_SIZE = 3
    const val SHOTGUN_RELOAD_DURATION_MS = RELOAD_DURATION_MS
    const val SHOTGUN_HIT_ANGLE_TOLERANCE_DEGREES = 40f
    const val SHOTGUN_PROJECTILE_SPEED_DP_PER_SEC = 700f
    const val SHOTGUN_PELLET_TRAIL_COUNT = 5
    const val SHOTGUN_PELLET_SPREAD_DEGREES = 12f
    const val SHOTGUN_PELLET_SIZE_FACTOR = 0.6f

    // Hunting bow — a hold/charge before firing, silent, speed scales with pull distance
    // (see BowMath). Reusing the pistol's reload/tolerance numbers; only magazine size,
    // charge, and speed differ.
    const val BOW_MAGAZINE_SIZE = 4
    const val BOW_RELOAD_DURATION_MS = RELOAD_DURATION_MS
    const val BOW_HIT_ANGLE_TOLERANCE_DEGREES = HIT_ANGLE_TOLERANCE_DEGREES
    const val BOW_MIN_CHARGE_HOLD_MS = 600L
    const val BOW_MIN_SPEED_DP_PER_SEC = 500f
    const val BOW_MAX_SPEED_DP_PER_SEC = 1800f

    // Sniper rifle — precise (narrow tolerance), fast, loud, and the only weapon that
    // broadcasts a live aim-heading telemetry field while being aimed (see MotionUpdate).
    const val SNIPER_MAGAZINE_SIZE = 2
    const val SNIPER_RELOAD_DURATION_MS = RELOAD_DURATION_MS
    const val SNIPER_HIT_ANGLE_TOLERANCE_DEGREES = 8f
    const val SNIPER_PROJECTILE_SPEED_DP_PER_SEC = 1800f
    // Named SNIPER_AIM_DOT_* (not SNIPER_LASER_DOT_*) to avoid colliding with the unrelated
    // WeaponType.LASER's own LASER_* constant group just below.
    const val SNIPER_AIM_DOT_ORBIT_RADIUS_DP = 48f
    const val SNIPER_AIM_DOT_RADIUS_DP = 5f

    // Laser — a single continuous 1s sweep, silent, one charge. LASER_RELOAD_DURATION_MS's
    // clock starts only once the beam concludes (never at touch-down), so it never needs to
    // stay in any particular numeric relationship with LASER_BEAM_DURATION_MS.
    const val LASER_MAGAZINE_SIZE = 1
    const val LASER_RELOAD_DURATION_MS = 1200L
    const val LASER_HIT_ANGLE_TOLERANCE_DEGREES = HIT_ANGLE_TOLERANCE_DEGREES
    const val LASER_BEAM_DURATION_MS = 1000L
    const val LASER_BEAM_SAMPLE_INTERVAL_MS = 50L
    // The receiver's incoming-hit visual for a beam weapon — deliberately much shorter than
    // the point-projectile weapons' travel time, since a beam has no real "flight time".
    const val LASER_INCOMING_TRAVEL_DURATION_MS = 150L

    // Weapon crates — fall independently on each device (no network sync), capped to one
    // active at a time. A fired shot destroys the crate if its aim ray passes within
    // CRATE_HIT_RADIUS_DP of the crate's actual position (CrateMath.isRayHit) — a fixed-size
    // area around the crate's true position, not an angular tolerance (a crate is visible,
    // unlike the blind opponent, so its hit region shouldn't scale with distance from center the
    // way an angular cone would). Top/bottom Y are logical dp values (not tied to any real
    // device's screen height), the same "arbitrary but comfortably large, and shared [by
    // renderer and hit-test]" convention already used by PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP.
    const val CRATE_SPAWN_MIN_INTERVAL_MS = 8_000L
    const val CRATE_SPAWN_MAX_INTERVAL_MS = 15_000L
    const val CRATE_FALL_DURATION_MS = 7_000L
    const val CRATE_HIT_RADIUS_DP = 80f
    const val CRATE_SPAWN_TOP_Y_DP = -600f
    const val CRATE_LAND_BOTTOM_Y_DP = 600f
    const val CRATE_SIZE_DP = 40f
    const val CRATE_WOBBLE_AMPLITUDE_DP = 24f
    const val CRATE_WOBBLE_FREQUENCY = 2.2f
    const val CRATE_LANE_MARGIN_DP = 60f

    // Crate destruction — a shot that hits a falling crate is "spent" on the crate instead of
    // being aimed at the opponent (see DuelViewModel.fireAtCrate): its outgoing projectile
    // animates only as far as the crate's current position (never the full off-screen travel
    // distance), so a minimum floor keeps that tween from collapsing to ~0ms for a crate that
    // happens to be very close to center at fire time.
    const val CRATE_HIT_MIN_TRAVEL_DURATION_MS = 90L
    const val CRATE_EXPLOSION_DURATION_MS = 500
    const val CRATE_EXPLOSION_PARTICLE_COUNT = 14
    const val CRATE_EXPLOSION_PARTICLE_MAX_DISTANCE_DP = 100f
    const val CRATE_EXPLOSION_PARTICLE_SIZE_DP = 6f
    const val CRATE_EXPLOSION_FLASH_DURATION_MS = 220L
    const val CRATE_EXPLOSION_FLASH_PEAK_ALPHA = 0.3f
    const val CRATE_EXPLOSION_SHAKE_MAGNITUDE_DP = 8f
    const val CRATE_EXPLOSION_SHAKE_CYCLES_X = 4f
    const val CRATE_EXPLOSION_SHAKE_CYCLES_Y = 5f

    // A raw numeric field on FireEvent (bow's per-shot travel duration) must be clamped on
    // receipt like every other peer-supplied number — comfortably above the slowest
    // configured weapon's real duration.
    const val MAX_PROJECTILE_TRAVEL_DURATION_MS = 5_000L

    // Cosmetic-only DuelScreen rendering ratios — not gameplay-affecting, but kept here
    // (rather than as private constants in DuelScreen.kt) for consistency with every other
    // "purely cosmetic but still a shared tunable" constant in this file, e.g. SHOTGUN_PELLET_*.
    const val OUT_OF_AMMO_AIM_ALPHA = 0.35f
    const val LASER_BEAM_WIDTH_MULTIPLIER = 2.5f

    // Per-weapon aim-line shape (drawn while dragging, before release/fire) — purely cosmetic,
    // hit-testing is never affected by any of these. LASER has no entry here: its "aim line" is
    // the full sweeping beam itself (drawn separately once the beam starts), so the generic
    // short aim indicator is skipped entirely for that weapon rather than drawing a redundant
    // second line on top of it.
    const val SHOTGUN_AIM_CONE_HALF_ANGLE_DEGREES = 16f
    const val SHOTGUN_AIM_CONE_FILL_ALPHA_FACTOR = 0.18f
    const val SNIPER_AIM_RETICLE_RADIUS_DP = 18f
    const val SNIPER_AIM_RETICLE_TICK_LENGTH_DP = 10f
    const val SNIPER_AIM_RETICLE_TICK_STROKE_FACTOR = 0.6f
    const val BOW_AIM_ARROWHEAD_LENGTH_DP = 20f
    const val BOW_AIM_ARROWHEAD_WIDTH_DP = 16f

    // Directional hit-particle splash — a spray of debris kicked off the side of the character
    // facing the incoming shot, on top of the existing symmetric explosion rings. Seeded off
    // lastHitAtMs (see DuelScreen) so the spray is deterministic per hit, not re-rolled per frame.
    const val HIT_PARTICLE_COUNT = 10
    const val HIT_PARTICLE_SPREAD_DEGREES = 50f
    const val HIT_PARTICLE_MAX_DISTANCE_DP = 90f
    const val HIT_PARTICLE_SIZE_DP = 5f
    // Shared floor for a particle burst's randomized per-particle speed — used by both the
    // directional hit-particle spray above and the omnidirectional crate-explosion burst (see
    // DuelScreen.randomSpraySeeds), not specific to either one.
    const val PARTICLE_BURST_MIN_SPEED_FACTOR = 0.5f
    // Shared per-particle shrink-over-lifetime factor for the same two particle bursts.
    const val PARTICLE_SHRINK_FACTOR = 0.5f

    // Incoming-hit "absorption" — as a hit projectile's travel progress nears 1 (arriving at the
    // character, distance -> 0), its head shrinks/fades toward this floor instead of just
    // reaching center and popping, selling the look of sinking into the character rather than
    // stopping short. Never applied to a miss (which overshoots past the character instead) or
    // to any outgoing shot.
    const val PROJECTILE_ARRIVAL_SHRINK_FACTOR = 0.9f

    /** Assembles the flat per-weapon constants above into one [WeaponConfig] per [WeaponType]. */
    object WeaponConfigs {
        val PISTOL = WeaponConfig(
            type = WeaponType.PISTOL,
            displayName = "Pistol",
            magazineSize = MAGAZINE_SIZE,
            reloadDurationMs = RELOAD_DURATION_MS,
            reloadPerfectZoneStartFraction = RELOAD_PERFECT_ZONE_START_FRACTION,
            reloadPerfectZoneEndFraction = RELOAD_PERFECT_ZONE_END_FRACTION,
            reloadMissPenaltyMs = RELOAD_MISS_PENALTY_MS,
            hitToleranceDegrees = HIT_ANGLE_TOLERANCE_DEGREES,
            damage = HIT_DAMAGE,
            isLoud = true,
            projectileSpeedDpPerSec = PROJECTILE_SPEED_DP_PER_SEC,
            projectileOffscreenTravelTimeMs = PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS,
        )
        val SHOTGUN = WeaponConfig(
            type = WeaponType.SHOTGUN,
            displayName = "Shotgun",
            magazineSize = SHOTGUN_MAGAZINE_SIZE,
            reloadDurationMs = SHOTGUN_RELOAD_DURATION_MS,
            reloadPerfectZoneStartFraction = RELOAD_PERFECT_ZONE_START_FRACTION,
            reloadPerfectZoneEndFraction = RELOAD_PERFECT_ZONE_END_FRACTION,
            reloadMissPenaltyMs = RELOAD_MISS_PENALTY_MS,
            hitToleranceDegrees = SHOTGUN_HIT_ANGLE_TOLERANCE_DEGREES,
            damage = HIT_DAMAGE,
            isLoud = true,
            projectileSpeedDpPerSec = SHOTGUN_PROJECTILE_SPEED_DP_PER_SEC,
            projectileOffscreenTravelTimeMs = PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS,
        )
        val BOW = WeaponConfig(
            type = WeaponType.BOW,
            displayName = "Hunting Bow",
            magazineSize = BOW_MAGAZINE_SIZE,
            reloadDurationMs = BOW_RELOAD_DURATION_MS,
            reloadPerfectZoneStartFraction = RELOAD_PERFECT_ZONE_START_FRACTION,
            reloadPerfectZoneEndFraction = RELOAD_PERFECT_ZONE_END_FRACTION,
            reloadMissPenaltyMs = RELOAD_MISS_PENALTY_MS,
            hitToleranceDegrees = BOW_HIT_ANGLE_TOLERANCE_DEGREES,
            damage = HIT_DAMAGE,
            isLoud = false,
            // Genuinely unused for the bow — DuelViewModel.fireBow derives both its visual
            // travel duration and its network send delay directly from the per-shot speed
            // (bowMinSpeedDpPerSec..bowMaxSpeedDpPerSec), never reading these two fields. Left
            // at 0 (not a plausible-looking fallback value) so an unused read is obviously wrong
            // rather than silently working with a misleading number.
            projectileSpeedDpPerSec = 0f,
            projectileOffscreenTravelTimeMs = 0L,
            bowMinChargeHoldMs = BOW_MIN_CHARGE_HOLD_MS,
            bowMinSpeedDpPerSec = BOW_MIN_SPEED_DP_PER_SEC,
            bowMaxSpeedDpPerSec = BOW_MAX_SPEED_DP_PER_SEC,
        )
        val SNIPER = WeaponConfig(
            type = WeaponType.SNIPER,
            displayName = "Sniper Rifle",
            magazineSize = SNIPER_MAGAZINE_SIZE,
            reloadDurationMs = SNIPER_RELOAD_DURATION_MS,
            reloadPerfectZoneStartFraction = RELOAD_PERFECT_ZONE_START_FRACTION,
            reloadPerfectZoneEndFraction = RELOAD_PERFECT_ZONE_END_FRACTION,
            reloadMissPenaltyMs = RELOAD_MISS_PENALTY_MS,
            hitToleranceDegrees = SNIPER_HIT_ANGLE_TOLERANCE_DEGREES,
            damage = HIT_DAMAGE,
            isLoud = true,
            projectileSpeedDpPerSec = SNIPER_PROJECTILE_SPEED_DP_PER_SEC,
            projectileOffscreenTravelTimeMs = PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS,
        )
        val LASER = WeaponConfig(
            type = WeaponType.LASER,
            displayName = "Laser",
            magazineSize = LASER_MAGAZINE_SIZE,
            reloadDurationMs = LASER_RELOAD_DURATION_MS,
            reloadPerfectZoneStartFraction = RELOAD_PERFECT_ZONE_START_FRACTION,
            reloadPerfectZoneEndFraction = RELOAD_PERFECT_ZONE_END_FRACTION,
            reloadMissPenaltyMs = RELOAD_MISS_PENALTY_MS,
            hitToleranceDegrees = LASER_HIT_ANGLE_TOLERANCE_DEGREES,
            damage = HIT_DAMAGE,
            isLoud = false,
            projectileSpeedDpPerSec = PROJECTILE_SPEED_DP_PER_SEC,
            projectileOffscreenTravelTimeMs = PROJECTILE_OFFSCREEN_TRAVEL_TIME_MS,
            laserBeamDurationMs = LASER_BEAM_DURATION_MS,
            laserSampleIntervalMs = LASER_BEAM_SAMPLE_INTERVAL_MS,
        )

        fun forType(type: WeaponType): WeaponConfig = when (type) {
            WeaponType.PISTOL -> PISTOL
            WeaponType.SHOTGUN -> SHOTGUN
            WeaponType.BOW -> BOW
            WeaponType.SNIPER -> SNIPER
            WeaponType.LASER -> LASER
        }

        init {
            // FireEvent.weaponType is peer-supplied and trusted as a damage-lookup key (never a
            // raw damage number) specifically because every weapon deals identical damage today —
            // this is only safe under that assumption. If a future tuning pass ever differentiates
            // per-weapon damage, a modified client could claim the highest-damage weapon on every
            // shot regardless of its real loadout, silently reopening the exact unbounded-damage
            // cheat vector this project's anti-cheat model already fixed once (see CLAUDE.md).
            // Fails loudly instead: differentiating damage requires first revisiting
            // DuelViewModel.onFireReceived's trust model, not just editing a constant here.
            check(listOf(PISTOL, SHOTGUN, BOW, SNIPER, LASER).all { it.damage == HIT_DAMAGE }) {
                "Per-weapon damage has diverged from HIT_DAMAGE — see the comment above this check."
            }
        }
    }
}
