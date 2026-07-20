package com.tolstykh.blindduel.connection

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

    @Serializable
    @SerialName("motion_update")
    data class MotionUpdate(
        val headingDegrees: Float,
        val stepVectorXMeters: Float,
        val stepVectorYMeters: Float,
    ) : GameMessage

    // No damage field: the receiver always applies GameConstants.HIT_DAMAGE locally rather
    // than trusting a peer-supplied amount (a modified client could otherwise send an
    // unbounded or negative value and make itself unkillable).
    @Serializable
    @SerialName("fire_event")
    data class FireEvent(val hit: Boolean) : GameMessage

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
