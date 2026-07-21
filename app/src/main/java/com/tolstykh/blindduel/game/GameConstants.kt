package com.tolstykh.blindduel.game

/**
 * Every tunable number for the duel mechanic lives here so gameplay feel can be
 * retuned in one place without hunting through screens/view models.
 */
object GameConstants {
    // Health / combat
    const val MAX_PLAYER_HEALTH = 3
    const val HIT_DAMAGE = 1

    // Cooldown — the ring polls frequently for smooth progress, but the displayed
    // seconds-remaining only ever changes once per second (Cooldown.secondsRemaining
    // ceils), satisfying the "ticks once per second" request.
    const val FIRE_COOLDOWN_MS = 2500L
    const val COOLDOWN_RING_POLL_INTERVAL_MS = 50L

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
}
