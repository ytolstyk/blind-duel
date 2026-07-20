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

    // Character
    const val CHARACTER_SIZE_DP = 72f

    // Damage feedback
    const val DAMAGE_FLASH_DURATION_MS = 300L
    const val DAMAGE_FLASH_PEAK_ALPHA = 0.35f
    const val DAMAGE_OVERLAY_ALPHA_MULTIPLIER = 0.4f

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

    // Ambient effects
    const val AMBIENT_PARTICLE_COUNT = 40

    // Connection
    const val SESSION_CODE_LENGTH = 6
    const val NEARBY_SERVICE_ID = "com.tolstykh.blindduel.SERVICE"
}
