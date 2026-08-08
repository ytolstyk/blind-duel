package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.GameConstants
import javax.inject.Inject
import javax.inject.Singleton

/** Which side sent a [RecordedMessage] — [MessageOrigin.LOCAL] for this device's own outgoing
 * sends, [MessageOrigin.REMOTE] for messages received from the opponent. */
enum class MessageOrigin { LOCAL, REMOTE }

/** One recorded [GameMessage], timestamped as milliseconds elapsed since recording started. */
data class RecordedMessage(val elapsedMs: Long, val origin: MessageOrigin, val message: GameMessage)

/**
 * In-memory recording of a duel's [GameMessage] stream, for spectator/replay mode
 * (`ui/replay/ReplayViewModel.kt`). Records only during Duel, not Calibration — Calibration has
 * no player-position data yet (step vectors are zero pre-first-step), and the one value replay
 * needs from Calibration ([com.tolstykh.blindduel.game.GameSession.myBaselineHeadingDegrees])
 * already lives in [com.tolstykh.blindduel.game.GameSession].
 *
 * Follows [com.tolstykh.blindduel.game.GameSession]'s encapsulation discipline: every field is
 * `private set`, mutated only through the named methods below — never an exposed mutable list.
 * In-memory only, per scope: no persistence, cleared by the next duel's [startRecording].
 */
@Singleton
class ReplayRecorder @Inject constructor() {
    private val messages = mutableListOf<RecordedMessage>()
    private var startedAtMs: Long = 0L

    var isRecording: Boolean = false
        private set

    /** Set once [GameConstants.REPLAY_MAX_RECORDED_MESSAGES] is reached — surfaced in the UI
     * (a caption on ReplayScreen) rather than silently dropping further messages, since the
     * messages most likely to be lost in a long/close match are exactly the outcome-defining
     * ones near the end. */
    var wasTruncated: Boolean = false
        private set

    val hasRecording: Boolean get() = messages.isNotEmpty()

    fun startRecording() {
        messages.clear()
        startedAtMs = System.currentTimeMillis()
        isRecording = true
        wasTruncated = false
    }

    fun record(origin: MessageOrigin, message: GameMessage) {
        if (!isRecording) return
        if (messages.size >= GameConstants.REPLAY_MAX_RECORDED_MESSAGES) {
            wasTruncated = true
            return
        }
        messages.add(RecordedMessage(System.currentTimeMillis() - startedAtMs, origin, message))
    }

    fun stopRecording() {
        isRecording = false
    }

    fun snapshot(): List<RecordedMessage> = messages.toList()
}
