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
    @Serializable
    @SerialName("hello")
    data class Hello(val name: String) : GameMessage

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
