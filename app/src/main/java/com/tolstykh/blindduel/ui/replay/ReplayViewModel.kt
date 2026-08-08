package com.tolstykh.blindduel.ui.replay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.MessageOrigin
import com.tolstykh.blindduel.connection.ReplayRecorder
import com.tolstykh.blindduel.game.BearingModel
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.game.TimeSeriesSampling
import com.tolstykh.blindduel.game.TimeSeriesSampling.Sample
import com.tolstykh.blindduel.game.Vector2
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reconstructs both players' position/heading/health over time from a recorded match (see
 * [ReplayRecorder]) using [com.tolstykh.blindduel.game.DuelEngine]'s existing pure math — the
 * same frame `DuelViewModel` already computed live, just replayed over time instead of read
 * live. Deliberately not a literal replay through `DuelViewModel`'s own code path: this
 * ViewModel's local-side state there is driven directly by live sensor flows, not messages, so
 * replaying through it would require injecting a fake sensor stream and suppressing every side
 * effect (sendMessage, haptics, sound, GameSession mutation, crate/reload/dodge machinery) —
 * more invasive than this small, purpose-built reconstruction.
 *
 * Since each device only ever replays its own local recording (no mutual confirm, per scope),
 * there's no cross-device frame reconciliation to invent.
 */
@HiltViewModel
class ReplayViewModel @Inject constructor(
    private val gameSession: GameSession,
    replayRecorder: ReplayRecorder,
) : ViewModel() {

    val localPlayerName: String = gameSession.localPlayerName
    val opponentName: String = gameSession.opponentName
    val wasTruncated: Boolean = replayRecorder.wasTruncated
    val matchMaxHealth: Int = gameSession.matchMaxHealth

    private val myBaselineHeadingDegrees: Float = checkNotNull(gameSession.myBaselineHeadingDegrees) {
        "ReplayViewModel requires calibration to have completed first"
    }
    private val opponentBaselineHeadingDegrees: Float = checkNotNull(gameSession.opponentBaselineHeadingDegrees) {
        "ReplayViewModel requires calibration to have completed first"
    }
    private val initialOpponentPosition = BearingModel.computeInitialOpponentPosition(
        baselineHeadingDegrees = myBaselineHeadingDegrees,
        assumedDistanceMeters = GameConstants.ASSUMED_INITIAL_DISTANCE_METERS,
    )

    private val snapshot = replayRecorder.snapshot()

    // localPositionSamples are already absolute in "my frame" (I'm the origin, matching how the
    // live model always treats my own position — see BearingModel.computeBearingToOpponentOnScreen).
    // remotePositionSamples are the opponent's own reported step vector, not yet offset by
    // initialOpponentPosition — offset applied lazily in opponentPositionAt below.
    private val localPositionSamples: List<Sample<Vector2>> =
        motionSamples(MessageOrigin.LOCAL) { Vector2(it.stepVectorXMeters, it.stepVectorYMeters) }
    private val remotePositionSamples: List<Sample<Vector2>> =
        motionSamples(MessageOrigin.REMOTE) { Vector2(it.stepVectorXMeters, it.stepVectorYMeters) }
    private val localHeadingSamples: List<Sample<Float>> =
        motionSamples(MessageOrigin.LOCAL) { it.headingDegrees }
    private val remoteHeadingSamples: List<Sample<Float>> =
        motionSamples(MessageOrigin.REMOTE) { it.headingDegrees }

    // A LOCAL-origin HealthUpdate is this device's own broadcast of its own health (each device
    // is authoritative only for its own health — see GameMessage's doc); a REMOTE-origin one is
    // the opponent's.
    private val myHealthSamples: List<Sample<Int>> =
        healthSamples(MessageOrigin.LOCAL)
    private val opponentHealthSamples: List<Sample<Int>> =
        healthSamples(MessageOrigin.REMOTE)

    // A LOCAL FireEvent(hit=true) is a shot I fired landing on the opponent -> flash the
    // opponent's dot. A REMOTE FireEvent(hit=true) is the opponent's shot landing on me -> flash
    // my own dot. Named outcome-first (hitsLandedOn___) rather than origin-first
    // (myHitEvents/opponentHitEvents) deliberately — the origin-first naming inverts relative to
    // the adjacent my/opponentHealthSamples pair above (my <-> LOCAL, opponent <-> REMOTE there),
    // which invites misreading this pair the same way and getting it backwards.
    private val hitsLandedOnOpponent: List<Sample<Long>> = fireHitSamples(MessageOrigin.LOCAL)
    private val hitsLandedOnMe: List<Sample<Long>> = fireHitSamples(MessageOrigin.REMOTE)

    val totalDurationMs: Long = snapshot.maxOfOrNull { it.elapsedMs } ?: 0L

    private val worldMinX: Float
    private val worldMaxX: Float
    private val worldMinY: Float
    private val worldMaxY: Float

    init {
        val allPositions = localPositionSamples.map { it.value } +
            remotePositionSamples.map { initialOpponentPosition + it.value } +
            listOf(Vector2.ZERO, initialOpponentPosition)
        worldMinX = allPositions.minOf { it.x } - GameConstants.REPLAY_VIEW_PADDING_METERS
        worldMaxX = allPositions.maxOf { it.x } + GameConstants.REPLAY_VIEW_PADDING_METERS
        worldMinY = allPositions.minOf { it.y } - GameConstants.REPLAY_VIEW_PADDING_METERS
        worldMaxY = allPositions.maxOf { it.y } + GameConstants.REPLAY_VIEW_PADDING_METERS
    }

    /** playbackPositionMs ticks at up to ~8Hz — read only inside a dedicated nested composable
     * (ReplayCanvas), never in ReplayScreen's own body, mirroring DuelReloadRing's isolation. */
    var playbackPositionMs by mutableStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var myHitFlashAtMs by mutableStateOf<Long?>(null)
        private set
    var opponentHitFlashAtMs by mutableStateOf<Long?>(null)
        private set

    /** Fixed camera scale, computed only once both the recorded position bounds (known
     * immediately) and the canvas's measured pixel size (known once, from the first
     * composition — see [setCanvasSize]) are available. Mirrors the DuelViewModel/DuelScreen
     * densityPxPerDp split: this ViewModel has no Compose/Android dependency of its own. */
    var scalePxPerMeter by mutableStateOf(0f)
        private set

    // The cursor eventsInWindow needs — reset to 0 alongside playbackPositionMs on restart(), so
    // a post-restart tick neither replays every past event again nor skips ahead.
    private var previousTickMs = 0L
    private var canvasWidthPx = 0f
    private var canvasHeightPx = 0f
    private var tickerJob: Job? = null

    fun setCanvasSize(widthPx: Float, heightPx: Float) {
        if (widthPx == canvasWidthPx && heightPx == canvasHeightPx) return
        canvasWidthPx = widthPx
        canvasHeightPx = heightPx
        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) return
        val worldWidthMeters = (worldMaxX - worldMinX).coerceAtLeast(GameConstants.ASSUMED_INITIAL_DISTANCE_METERS)
        val worldHeightMeters = (worldMaxY - worldMinY).coerceAtLeast(GameConstants.ASSUMED_INITIAL_DISTANCE_METERS)
        scalePxPerMeter = min(canvasWidthPx / worldWidthMeters, canvasHeightPx / worldHeightMeters)
    }

    /** World meters -> screen px, centered on the fixed camera's world bounds. */
    fun worldToScreenOffsetPx(worldPos: Vector2, canvasCenterPx: Offset): Offset {
        val worldCenterX = (worldMinX + worldMaxX) / 2f
        val worldCenterY = (worldMinY + worldMaxY) / 2f
        // Screen y increases downward while world y is "north" (up) — hence the sign flip, the
        // same relationship BearingModel.headingToVector/DuelScreen.offsetFromCenter already
        // encode (a world vector's x/y IS already distance*sin/cos of its own heading, so this
        // is that same sign convention applied directly to an absolute position instead of a
        // bearing+distance pair).
        return Offset(
            canvasCenterPx.x + (worldPos.x - worldCenterX) * scalePxPerMeter,
            canvasCenterPx.y - (worldPos.y - worldCenterY) * scalePxPerMeter,
        )
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun restart() {
        pause()
        playbackPositionMs = 0L
        previousTickMs = 0L
        myHitFlashAtMs = null
        opponentHitFlashAtMs = null
        play()
    }

    private fun play() {
        if (totalDurationMs <= 0L) return
        if (playbackPositionMs >= totalDurationMs) {
            playbackPositionMs = 0L
            previousTickMs = 0L
        }
        isPlaying = true
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isPlaying && playbackPositionMs < totalDurationMs) {
                delay(GameConstants.REPLAY_PLAYBACK_TICK_MS)
                advanceTo((playbackPositionMs + GameConstants.REPLAY_PLAYBACK_TICK_MS).coerceAtMost(totalDurationMs))
            }
            isPlaying = false
        }
    }

    private fun pause() {
        isPlaying = false
        tickerJob?.cancel()
    }

    private fun advanceTo(nextMs: Long) {
        if (TimeSeriesSampling.eventsInWindow(hitsLandedOnMe, previousTickMs, nextMs).isNotEmpty()) {
            myHitFlashAtMs = nextMs
        }
        if (TimeSeriesSampling.eventsInWindow(hitsLandedOnOpponent, previousTickMs, nextMs).isNotEmpty()) {
            opponentHitFlashAtMs = nextMs
        }
        previousTickMs = nextMs
        playbackPositionMs = nextMs
    }

    fun localPositionAt(tMs: Long): Vector2 =
        TimeSeriesSampling.sampleAtOrBefore(localPositionSamples, tMs, Vector2.ZERO)

    fun opponentPositionAt(tMs: Long): Vector2 =
        initialOpponentPosition + TimeSeriesSampling.sampleAtOrBefore(remotePositionSamples, tMs, Vector2.ZERO)

    fun localHeadingAt(tMs: Long): Float =
        TimeSeriesSampling.sampleAtOrBefore(localHeadingSamples, tMs, myBaselineHeadingDegrees)

    fun opponentHeadingAt(tMs: Long): Float =
        TimeSeriesSampling.sampleAtOrBefore(remoteHeadingSamples, tMs, opponentBaselineHeadingDegrees)

    fun myHealthAt(tMs: Long): Int =
        TimeSeriesSampling.sampleAtOrBefore(myHealthSamples, tMs, gameSession.matchMaxHealth)

    fun opponentHealthAt(tMs: Long): Int =
        TimeSeriesSampling.sampleAtOrBefore(opponentHealthSamples, tMs, gameSession.matchMaxHealth)

    // Sorted once here, at construction — TimeSeriesSampling.sampleAtOrBefore/eventsInWindow both
    // require pre-sorted input (see their own docs) so playback ticks don't pay for an O(n log n)
    // re-sort ~8x/sec for the whole replay. A recorded message's timestamp is captured before it
    // passes through the sender's own send-serialization, so strict insertion order isn't
    // guaranteed across different call sites — this sort is what actually establishes the
    // precondition those functions rely on, not just a defensive no-op.
    private inline fun <T> motionSamples(origin: MessageOrigin, crossinline extract: (GameMessage.MotionUpdate) -> T): List<Sample<T>> =
        snapshot.filter { it.origin == origin }
            .mapNotNull { recorded -> (recorded.message as? GameMessage.MotionUpdate)?.let { Sample(recorded.elapsedMs, extract(it)) } }
            .sortedBy { it.atMs }

    private fun healthSamples(origin: MessageOrigin): List<Sample<Int>> =
        snapshot.filter { it.origin == origin }
            .mapNotNull { recorded ->
                (recorded.message as? GameMessage.HealthUpdate)?.let {
                    // Recomputed/clamped here rather than trusting the recorded peer-supplied
                    // value directly, matching this project's documented anti-cheat convention
                    // (see CLAUDE.md) — DuelViewModel.handleIncoming already clamps a REMOTE
                    // HealthUpdate before touching UI state; this keeps replay's rendering
                    // consistent with that same bound instead of trusting the raw recorded Int.
                    Sample(recorded.elapsedMs, it.remainingHealth.coerceIn(0, gameSession.matchMaxHealth))
                }
            }
            .sortedBy { it.atMs }

    private fun fireHitSamples(origin: MessageOrigin): List<Sample<Long>> =
        snapshot.filter { it.origin == origin }
            .mapNotNull { recorded ->
                (recorded.message as? GameMessage.FireEvent)?.takeIf { it.hit }?.let { Sample(recorded.elapsedMs, recorded.elapsedMs) }
            }
            .sortedBy { it.atMs }

    override fun onCleared() {
        tickerJob?.cancel()
    }
}
