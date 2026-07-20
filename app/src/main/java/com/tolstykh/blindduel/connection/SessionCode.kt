package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.GameConstants
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Short pairing code advertised over Nearby Connections and shown/scanned as a QR.
 * Alphabet excludes visually ambiguous characters (0/O, 1/I) — at ~32^6 combinations,
 * collisions across simultaneous party games are negligible.
 */
object SessionCode {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val QR_SCHEME_PREFIX = "blindduel://"

    fun generate(): String = buildString {
        repeat(GameConstants.SESSION_CODE_LENGTH) {
            append(ALPHABET[Random.nextInt(ALPHABET.length)])
        }
    }

    fun encodeQrPayload(sessionCode: String, hostName: String): String =
        "$QR_SCHEME_PREFIX$sessionCode/${URLEncoder.encode(hostName, "UTF-8")}"

    /** Returns (sessionCode, hostName), or null if [payload] isn't a BlindDuel QR code. */
    fun decodeQrPayload(payload: String): Pair<String, String>? {
        if (!payload.startsWith(QR_SCHEME_PREFIX)) return null
        val remainder = payload.removePrefix(QR_SCHEME_PREFIX)
        val slashIndex = remainder.indexOf('/')
        if (slashIndex <= 0) return null
        val code = remainder.substring(0, slashIndex)
        // A hostile/corrupted QR code (e.g. a broken percent-escape) must not crash the
        // scanner — URLDecoder throws IllegalArgumentException on malformed input.
        val name = runCatching { URLDecoder.decode(remainder.substring(slashIndex + 1), "UTF-8") }
            .getOrNull() ?: return null
        if (code.isEmpty() || name.isEmpty()) return null
        return code to name
    }
}
