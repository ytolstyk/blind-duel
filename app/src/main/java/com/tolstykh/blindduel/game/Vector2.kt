package com.tolstykh.blindduel.game

/** A 2D offset in meters, in the shared magnetic-north-relative frame used for dead reckoning. */
data class Vector2(val x: Float, val y: Float) {
    operator fun plus(other: Vector2) = Vector2(x + other.x, y + other.y)
    operator fun minus(other: Vector2) = Vector2(x - other.x, y - other.y)

    companion object {
        val ZERO = Vector2(0f, 0f)
    }
}
