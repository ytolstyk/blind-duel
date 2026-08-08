package com.tolstykh.blindduel.connection

import com.tolstykh.blindduel.game.GameConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRecorderTest {
    @Test
    fun `startRecording clears prior state and sets isRecording`() {
        val recorder = ReplayRecorder()
        recorder.startRecording()
        recorder.record(MessageOrigin.LOCAL, GameMessage.Quit)
        assertTrue(recorder.isRecording)
        assertTrue(recorder.hasRecording)

        recorder.startRecording()
        assertTrue(recorder.isRecording)
        assertFalse(recorder.hasRecording)
    }

    @Test
    fun `record no-ops when not recording`() {
        val recorder = ReplayRecorder()
        recorder.record(MessageOrigin.LOCAL, GameMessage.Quit)
        assertFalse(recorder.hasRecording)
    }

    @Test
    fun `stopRecording freezes further recording`() {
        val recorder = ReplayRecorder()
        recorder.startRecording()
        recorder.record(MessageOrigin.LOCAL, GameMessage.Quit)
        recorder.stopRecording()
        assertFalse(recorder.isRecording)

        recorder.record(MessageOrigin.REMOTE, GameMessage.Quit)
        assertEquals(1, recorder.snapshot().size)
    }

    @Test
    fun `snapshot is an immutable copy`() {
        val recorder = ReplayRecorder()
        recorder.startRecording()
        recorder.record(MessageOrigin.LOCAL, GameMessage.Quit)
        val firstSnapshot = recorder.snapshot()

        recorder.record(MessageOrigin.LOCAL, GameMessage.ReadyForDuel)

        assertEquals(1, firstSnapshot.size)
        assertEquals(2, recorder.snapshot().size)
    }

    @Test
    fun `wasTruncated resets on startRecording`() {
        val recorder = ReplayRecorder()
        recorder.startRecording()
        repeat(GameConstants.REPLAY_MAX_RECORDED_MESSAGES) {
            recorder.record(MessageOrigin.LOCAL, GameMessage.ReadyForDuel)
        }
        recorder.record(MessageOrigin.LOCAL, GameMessage.ReadyForDuel)
        assertTrue(recorder.wasTruncated)

        recorder.startRecording()
        assertFalse(recorder.wasTruncated)
    }

    @Test
    fun `recording beyond the cap sets wasTruncated instead of failing silently`() {
        val recorder = ReplayRecorder()
        recorder.startRecording()
        val cap = GameConstants.REPLAY_MAX_RECORDED_MESSAGES
        repeat(cap + 5) {
            recorder.record(MessageOrigin.LOCAL, GameMessage.ReadyForDuel)
        }
        assertEquals(cap, recorder.snapshot().size)
        assertTrue(recorder.wasTruncated)
    }
}
