package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.WeaponType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire protocol exchanged over the peer-to-peer connection. See the plan's Message Protocol. */
@Serializable
sealed interface GameMessage {
    // maxHealth is the host's chosen match-length value (see MainMenuScreen's picker); the
    // joiner adopts it (clamped on receipt — see JoinViewModel) and the host's own Hello-
    // received handler deliberately ignores it, since the joiner never picks one of its own.
    // Defaults to the standard match length so an unset value is never a plausible-looking lie.
    @Serializable
    @SerialName("hello")
    data class Hello(val name: String, val maxHealth: Int = GameConstants.MAX_PLAYER_HEALTH) : GameMessage

    @Serializable
    @SerialName("ready_for_duel")
    data object ReadyForDuel : GameMessage

    @Serializable
    @SerialName("calibration_complete")
    data class CalibrationComplete(val baselineHeadingDegrees: Float) : GameMessage

    // sniperAimHeadingDegrees rides on the existing 120ms broadcast rather than a second
    // message/loop. It's an ABSOLUTE (magnetic-frame) heading — the shooter's current aim
    // direction combined with their own current compass heading — not a screen-relative
    // value, since a screen-relative angle has no shared meaning across two independently
    // oriented devices. Only non-null while equippedWeapon == SNIPER && actively aiming &&
    // canFire() (see DuelViewModel) — a reloading sniper must not keep leaking telemetry.
    @Serializable
    @SerialName("motion_update")
    data class MotionUpdate(
        val headingDegrees: Float,
        val stepVectorXMeters: Float,
        val stepVectorYMeters: Float,
        val sniperAimHeadingDegrees: Float? = null,
    ) : GameMessage

    // No damage field: the receiver always applies a fixed local damage lookup rather than
    // trusting a peer-supplied amount (a modified client could otherwise send an unbounded
    // or negative value and make itself unkillable). weaponType is trusted only as a lookup
    // key into WeaponConfigs (whose damage is uniformly GameConstants.HIT_DAMAGE anyway) and
    // for cosmetic tell/rendering selection — see CLAUDE.md's anti-cheat notes.
    // travelDurationMs is purely cosmetic animation timing (never consulted by health/
    // outcome math) so a lying peer can only desync an animation, never affect the result —
    // but it must still be clamped to GameConstants.MAX_PROJECTILE_TRAVEL_DURATION_MS on
    // receipt before feeding an Int-truncating tween()/delay(), the same way HealthUpdate's
    // remainingHealth is clamped below.
    @Serializable
    @SerialName("fire_event")
    data class FireEvent(
        val hit: Boolean,
        val weaponType: WeaponType = WeaponType.PISTOL,
        val travelDurationMs: Long = GameConstants.PROJECTILE_TRAVEL_DURATION_MS,
    ) : GameMessage

    /** "Here is MY current health" — each device is authoritative only for its own. */
    @Serializable
    @SerialName("health_update")
    data class HealthUpdate(val remainingHealth: Int) : GameMessage

    // Zero-payload: the receiver timestamps this on receipt (System.currentTimeMillis()),
    // never trusting a peer-supplied timestamp, which sidesteps clock skew entirely. The
    // receiver also enforces its own rate limit on top of the sender's cooldown-gated UI
    // (DuelViewModel.handleIncoming) — a modified client resending this faster than
    // GameConstants.DODGE_COOLDOWN_MS is ignored, bounding a spamming peer to the same duty
    // cycle a well-behaved client produces instead of an open-ended "always dodging" exploit.
    // The remaining accepted gap (no ack, so a message can arrive after the shot it should have
    // affected already resolved) is a UX rough edge, not a fairness bug — consistent with this
    // project's existing 2-peer, no-server-arbitration trust model (see FireEvent above).
    @Serializable
    @SerialName("dodge_event")
    data object DodgeEvent : GameMessage

    @Serializable
    @SerialName("rematch_request")
    data object RematchRequest : GameMessage

    @Serializable
    @SerialName("quit")
    data object Quit : GameMessage
}

private val gameMessageJson = Json { ignoreUnknownKeys = true }

fun GameMessage.toBytes(): ByteArray =
    gameMessageJson.encodeToString(this).encodeToByteArray()

fun ByteArray.toGameMessage(): GameMessage =
    gameMessageJson.decodeFromString(this.decodeToString())

/**
 * True unless a peer-supplied `Float` field is `NaN`/infinite. kotlinx.serialization's default
 * `Json` config (`allowSpecialFloatingPointValues = false`, unchanged in [gameMessageJson])
 * already rejects those at decode time today, so this is currently redundant in practice — but
 * that protection is an implicit library default, not an explicit, tested application invariant.
 * A non-finite [MotionUpdate.headingDegrees] would feed straight into `Canvas.rotate()` in
 * `ReplayScreen`, the first place in the app that renders a peer-supplied heading as a rotation
 * transform (the live duel never renders the opponent at all). Checked explicitly here so this
 * protection doesn't silently depend on that library default never changing.
 */
fun GameMessage.hasFiniteNumericFields(): Boolean = when (this) {
    is GameMessage.MotionUpdate ->
        headingDegrees.isFinite() && stepVectorXMeters.isFinite() && stepVectorYMeters.isFinite() &&
            (sniperAimHeadingDegrees?.isFinite() ?: true)
    is GameMessage.CalibrationComplete -> baselineHeadingDegrees.isFinite()
    else -> true
}
