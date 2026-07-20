package com.tolstykh.blindduel.permission

import android.Manifest
import android.os.Build

/**
 * Runtime permissions needed to host/join a duel. [ACTIVITY_RECOGNITION] is requested
 * alongside the rest but is never a hard blocker — see the plan's "Permission denial UX":
 * if denied, the bearing model just degrades to heading-only rather than blocking play.
 */
object GamePermissions {
    fun required(includeCamera: Boolean): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (includeCamera) {
            add(Manifest.permission.CAMERA)
        }
    }

    fun hardRequired(includeCamera: Boolean): List<String> =
        required(includeCamera).filterNot { it == Manifest.permission.ACTIVITY_RECOGNITION }
}
