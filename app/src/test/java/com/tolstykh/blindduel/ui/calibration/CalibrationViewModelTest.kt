package com.tolstykh.blindduel.ui.calibration

import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.ConnectionState
import com.tolstykh.blindduel.connection.GameConnection
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.sensor.MotionProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the reconnect-grace-period follow-on fix (see the plan's §1.5): a Calibration
 * handshake that never completes — because the one-shot ReadyForDuel/CalibrationComplete
 * message it was waiting on was lost during a reconnect gap — must not hang forever. It should
 * time out and call the connection's existing quit() extension, not introduce a new UI state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a handshake that never completes calls quit after its timeout`() = runTest(dispatcher) {
        val fakeConnection = mockk<GameConnection>()
        every { fakeConnection.connectionState } returns MutableStateFlow(ConnectionState.Connected("peer"))
        every { fakeConnection.incomingMessages } returns MutableSharedFlow()
        coEvery { fakeConnection.sendMessage(any()) } returns Unit
        var disconnectCalled = false
        every { fakeConnection.disconnect() } answers { disconnectCalled = true }

        val activeGameConnection = mockk<ActiveGameConnection>()
        every { activeGameConnection.current } returns fakeConnection

        val motionProvider = mockk<MotionProvider>()
        every { motionProvider.headingUpdates() } returns emptyFlow()

        CalibrationViewModel(GameSession(), activeGameConnection, motionProvider)

        // Never sends ReadyForDuel/CalibrationComplete — advance well past the handshake
        // timeout with no further interaction.
        dispatcher.scheduler.advanceTimeBy(GameConstants.CALIBRATION_HANDSHAKE_TIMEOUT_MS + 1_000L)
        dispatcher.scheduler.runCurrent()

        assertTrue("quit() (via disconnect()) should have fired after the handshake timeout", disconnectCalled)
    }

    @Test
    fun `a completed handshake does not call quit`() = runTest(dispatcher) {
        val incoming = MutableSharedFlow<GameMessage>(extraBufferCapacity = 4)
        val fakeConnection = mockk<GameConnection>()
        every { fakeConnection.connectionState } returns MutableStateFlow(ConnectionState.Connected("peer"))
        every { fakeConnection.incomingMessages } returns incoming
        coEvery { fakeConnection.sendMessage(any()) } returns Unit
        var disconnectCalled = false
        every { fakeConnection.disconnect() } answers { disconnectCalled = true }

        val activeGameConnection = mockk<ActiveGameConnection>()
        every { activeGameConnection.current } returns fakeConnection

        val motionProvider = mockk<MotionProvider>()
        every { motionProvider.headingUpdates() } returns emptyFlow()

        val viewModel = CalibrationViewModel(GameSession(), activeGameConnection, motionProvider)
        // MutableSharedFlow has no replay (replay = 0) — a value tryEmit'd before the
        // ViewModel's collector has actually subscribed (which only happens once the test
        // dispatcher actually runs the queued collector coroutine) is silently dropped, the
        // same "no replay" pitfall LoopbackGameConnection's own code comments warn about. Let
        // init's collectors subscribe first before emitting anything.
        dispatcher.scheduler.runCurrent()

        viewModel.onReadyClicked()
        incoming.tryEmit(GameMessage.ReadyForDuel)
        dispatcher.scheduler.runCurrent()

        // Let the 3-2-1 countdown fully elapse before delivering the opponent's baseline, so
        // the phase transition to AwaitingOpponentBaseline happens deterministically first.
        dispatcher.scheduler.advanceTimeBy(
            GameConstants.CALIBRATION_COUNTDOWN_SECONDS * GameConstants.CALIBRATION_COUNTDOWN_TICK_MS + 100L,
        )
        dispatcher.scheduler.runCurrent()

        incoming.tryEmit(GameMessage.CalibrationComplete(baselineHeadingDegrees = 90f))
        dispatcher.scheduler.runCurrent()

        // Well past both handshake timeouts, with no further interaction.
        dispatcher.scheduler.advanceTimeBy(GameConstants.CALIBRATION_HANDSHAKE_TIMEOUT_MS + 1_000L)
        dispatcher.scheduler.runCurrent()

        assertTrue("Calibration should have reached Ready", viewModel.phase == CalibrationPhase.Ready)
        assertTrue("quit() must not fire once the handshake completed normally", !disconnectCalled)
    }
}
