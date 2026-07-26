package com.tolstykh.blindduel.ui.duel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tolstykh.blindduel.connection.ActiveGameConnection
import com.tolstykh.blindduel.connection.GameMessage
import com.tolstykh.blindduel.connection.quit
import com.tolstykh.blindduel.connection.sessionEndedEvents
import com.tolstykh.blindduel.game.AngleMath
import com.tolstykh.blindduel.game.BearingModel
import com.tolstykh.blindduel.game.BowMath
import com.tolstykh.blindduel.game.CrateMath
import com.tolstykh.blindduel.game.DuelOutcome
import com.tolstykh.blindduel.game.FallingCrate
import com.tolstykh.blindduel.game.GameConstants
import com.tolstykh.blindduel.game.GameConstants.WeaponConfigs
import com.tolstykh.blindduel.game.GameSession
import com.tolstykh.blindduel.game.HealthMath
import com.tolstykh.blindduel.game.HitTest
import com.tolstykh.blindduel.game.LaserBeamHitAccumulator
import com.tolstykh.blindduel.game.OutcomeResolver
import com.tolstykh.blindduel.game.Reload
import com.tolstykh.blindduel.game.Vector2
import com.tolstykh.blindduel.game.WeaponConfig
import com.tolstykh.blindduel.game.WeaponType
import com.tolstykh.blindduel.sensor.Haptics
import com.tolstykh.blindduel.sensor.MotionProvider
import com.tolstykh.blindduel.sensor.WeaponSoundPlayer
import com.tolstykh.blindduel.sensor.isAccuracyLow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DuelUiState(
    val myHealth: Int = GameConstants.MAX_PLAYER_HEALTH,
    val opponentHealth: Int = GameConstants.MAX_PLAYER_HEALTH,
    val damageFlash: Boolean = false,
    val damageDirectionDegrees: Float? = null,
    val showAccuracyWarning: Boolean = false,
    val showAlignmentWarning: Boolean = false,
    val outcome: DuelOutcome = DuelOutcome.Ongoing,
    // Changes on every fired shot (not just reload start/end), same cadence as outgoingShot —
    // safe to read directly in DuelScreen's body only because it's always written in the same
    // synchronous call as outgoingShot, so they already coalesce into one recompose per shot. If
    // outgoingShot's write is ever decoupled from firing (e.g. moved behind a delay), this field
    // would become its own full-DuelScreen-recompose trigger and should move to an isolated
    // composable the way reloadProgress already is.
    val roundsRemaining: Int = GameConstants.MAGAZINE_SIZE,
    // Only changes on a crate pickup or a reload completing (auto-revert to pistol) — both
    // low-frequency events, so no recomposition-isolation concern the way reloadProgress has.
    val equippedWeapon: WeaponType = WeaponType.PISTOL,
    // Same trigger-flash pattern as damageFlash, just themed for a destroyed crate rather than
    // a landed opponent hit — see DuelViewModel.triggerCrateExplosion.
    val crateExplosionFlash: Boolean = false,
) {
    // Derived, not stored: startReload()/completeReload() always empty/refill roundsRemaining
    // in the same call that starts/ends a reload, so this can never desync from it. Flips at
    // most twice per magazine cycle — well under the ~5Hz recomposition-isolation threshold that
    // applies to reloadProgress below.
    val isReloading: Boolean get() = roundsRemaining <= 0
    val equippedWeaponConfig: WeaponConfig get() = WeaponConfigs.forType(equippedWeapon)
}

/**
 * A just-fired shot, for the shooter's own on-screen projectile animation. [travelDurationMs] is
 * per-shot (not a flat constant) so the bow's pull-distance-based speed animates correctly. This
 * slot holds at most one shot — with no per-shot cooldown, a fast rapid-fire burst can overwrite
 * it before its own animation finishes, which is why the "HIT" indicator (see
 * [DuelViewModel.hitConfirmedAtMs]) is scheduled independently from fire time rather than from
 * this animation completing, so a confirmed hit is never silently dropped just because a later
 * shot in the same burst replaced this slot first.
 *
 * A sealed type rather than one flat class with nullable "which target" fields: [AtOpponent] and
 * [AtCrate] are genuinely different variants (a crate shot has no "was the opponent hit"
 * question, an opponent shot has no crate id/offset), and a crate id + its captured offset must
 * always travel together — nullable fields on one class can't enforce that pairing, a sealed
 * type's own constructor does. Consumers ([DuelScreen], [onProjectileAnimationFinished])
 * `is`-check the variant instead of null-checking two fields that are supposed to agree.
 */
sealed class OutgoingShot {
    abstract val aimAngleDegrees: Float
    abstract val firedAtMs: Long
    abstract val weaponType: WeaponType
    abstract val travelDurationMs: Long

    /** How far (in dp) this shot's visual travel animates before disappearing — the full
     * off-screen distance for [AtOpponent], or the crate's own distance from center for
     * [AtCrate], so the projectile visibly stops at the crate instead of continuing on toward
     * where the opponent would be. */
    abstract val maxDistanceDp: Float

    /** A shot aimed at the (unseen) opponent — [wasHit] is already known locally at fire time,
     * the shooter decides hit/miss unilaterally. */
    data class AtOpponent(
        override val aimAngleDegrees: Float,
        override val firedAtMs: Long,
        override val weaponType: WeaponType,
        override val travelDurationMs: Long,
        val wasHit: Boolean,
    ) : OutgoingShot() {
        override val maxDistanceDp: Float = GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP
    }

    /** A shot whose aim ray hit the active falling crate instead of being aimed at the opponent —
     * see [DuelViewModel.fireAtCrate]. [offsetDp] is captured at fire time, not re-read later:
     * DuelScreen freezes the crate's rendered position at this exact value for the remainder of
     * this shot's flight (rather than continuing to read the crate's own live fall animation), so
     * the eventual explosion always appears exactly where the projectile visually arrives instead
     * of at a position the falling crate has since moved away from. */
    data class AtCrate(
        override val aimAngleDegrees: Float,
        override val firedAtMs: Long,
        override val weaponType: WeaponType,
        override val travelDurationMs: Long,
        val crateId: Long,
        val offsetDp: Vector2,
    ) : OutgoingShot() {
        override val maxDistanceDp: Float = hypot(offsetDp.x, offsetDp.y)
    }
}

/** A destroyed crate's one-shot explosion effect (rings + particle burst + screen flash/shake) —
 * [offsetDp] is captured at the moment of destruction since [DuelViewModel.activeCrate] is
 * cleared in the same call, so nothing else remembers where the crate was. */
data class CrateExplosionEffect(
    val triggeredAtMs: Long,
    val offsetDp: Vector2,
)

/** A crate-seeking shot that's still in flight, committed to destroying [crateId] once its travel
 * time elapses (see [DuelViewModel.fireAtCrate]). Deliberately tracked separately from
 * [DuelViewModel.outgoingShot] — that slot holds at most one shot and a rapid-fire burst (no
 * per-shot cooldown gates firing) can overwrite it with an unrelated shot before this one's own
 * animation finishes; if crate resolution depended on that slot still holding *this* shot when its
 * animation completes, an overwrite would silently drop the resolution — spent ammo, no
 * explosion, no weapon swap, and the crate would keep falling as if never hit. Resolution here is
 * instead scheduled independently (mirroring how [DuelViewModel.hitConfirmedAtMs] is scheduled
 * independently of the same slot for the exact same reason), and [DuelScreen] uses this field
 * (not [DuelViewModel.outgoingShot]) to freeze the crate's rendered position and to guard against
 * [DuelViewModel.onCrateLanded] racing the resolution. */
data class PendingCrateHit(
    val crateId: Long,
    val offsetDp: Vector2,
)

/** An opponent's shot arriving locally, for the receiver's incoming-projectile animation.
 * [bearingDegrees] is the receiver's own live bearing estimate to the opponent — there is no
 * ground truth for the shooter's actual aim, only where we currently believe they are.
 * [travelDurationMs] comes from the peer's FireEvent (clamped on receipt), so a bow's real
 * per-shot speed renders correctly instead of using a flat constant. */
data class IncomingShot(
    val bearingDegrees: Float,
    val hit: Boolean,
    val receivedAtMs: Long,
    val weaponType: WeaponType,
    val travelDurationMs: Long,
)

@HiltViewModel
class DuelViewModel @Inject constructor(
    private val gameSession: GameSession,
    private val activeGameConnection: ActiveGameConnection,
    private val motionProvider: MotionProvider,
    private val haptics: Haptics,
    private val weaponSoundPlayer: WeaponSoundPlayer,
) : ViewModel() {

    // Solo Test Mode lets the player pick a starting weapon from the main menu so any weapon can
    // be tested without waiting on a crate; GameSession.practiceStartingWeapon already defaults
    // to PISTOL outside practice mode, so a real match always starts on the pistol for free.
    private val startingWeapon = gameSession.practiceStartingWeapon

    var uiState by mutableStateOf(
        DuelUiState(
            showAlignmentWarning = !gameSession.isAlignedAtCalibration,
            equippedWeapon = startingWeapon,
            roundsRemaining = WeaponConfigs.forType(startingWeapon).magazineSize,
        ),
    )
        private set

    // Kept out of DuelUiState and updated at its own cadence: reloadProgress changes ~20x/sec
    // while a reload is active, and bundling it into the same state object as health/outcome/etc.
    // would force the whole DuelScreen to recompose on every tick instead of just the ReloadRing
    // that actually reads it.
    var reloadProgress by mutableStateOf(0f)
        private set
    var isInPerfectReloadZone by mutableStateOf(false)
        private set

    /** Trigger timestamp for the reload-complete animation — same pattern as [lastHitAtMs]. */
    var reloadCompletedAtMs by mutableStateOf<Long?>(null)
        private set

    /** Trigger timestamp for the reload ring's miss-tap red flash — same pattern as [lastHitAtMs]. */
    var reloadMissTapAtMs by mutableStateOf<Long?>(null)
        private set

    /** Raw drag offset from screen center, in px; magnitude is visual-only, hit-testing only uses
     * angle. Deliberately never gated by ammo/reload state (see [onAimDrag]) — the aim line and
     * crate targeting both need this to stay live even while out of ammo. */
    var aimDrag by mutableStateOf<Offset?>(null)
        private set

    var outgoingShot by mutableStateOf<OutgoingShot?>(null)
        private set

    /** Trigger timestamp for the "HIT" confirmation indicator — scheduled directly from fire
     * time (see [onAimRelease]) rather than from [outgoingShot]'s own animation completing, since
     * a rapid-fire burst (no per-shot cooldown gates this anymore) can overwrite [outgoingShot]
     * before an earlier shot's animation finishes; keying off an independent trigger timestamp
     * means every landed hit gets its own confirmation window regardless of later shots. */
    var hitConfirmedAtMs by mutableStateOf<Long?>(null)
        private set

    var incomingShot by mutableStateOf<IncomingShot?>(null)
        private set

    /** Timestamp of the most recent landed hit against me — a trigger key for the duel
     * screen's camera-shake effect, distinct from [outgoingShot] which triggers its own. */
    var lastHitAtMs by mutableStateOf<Long?>(null)
        private set

    /** Ambient decorative accelerometer reading (m/s^2, x/y) — feeds the particle backdrop's
     * swirl/parallax only, never gameplay. */
    var deviceTilt by mutableStateOf(Offset.Zero)
        private set

    var sessionEnded by mutableStateOf(false)
        private set

    /** ~20Hz while actively drawing the bow; 0 otherwise. Kept out of DuelUiState for the same
     * recomposition-isolation reason as [reloadProgress]. */
    var bowChargeProgress by mutableStateOf(0f)
        private set

    /** The laser's live sweep angle while its beam is active, null otherwise. Read directly
     * inside DuelScreen's main Canvas draw closure (no isolation needed — a plain live value
     * with no fade/smoothing, same treatment as the outgoing projectile's travel progress). */
    var laserBeamAngleDegrees by mutableStateOf<Float?>(null)
        private set

    /** The single active falling crate, or null. Capped at one at a time; only changes on
     * spawn/pickup/landing — all low-frequency events, so no isolation composable is needed
     * the way reloadProgress needs one. */
    var activeCrate by mutableStateOf<FallingCrate?>(null)
        private set

    /** The most recent crate destruction's explosion effect, or null. Cleared once its
     * DuelScreen-side animation finishes (see [onCrateExplosionAnimationFinished]) — same
     * trigger-timestamp pattern as [lastHitAtMs]. */
    var crateExplosion by mutableStateOf<CrateExplosionEffect?>(null)
        private set

    /** A crate-seeking shot still in flight, committed to destroying its target — see
     * [PendingCrateHit]'s own doc for why this is tracked separately from [outgoingShot]. Null
     * whenever no crate-hit resolution is currently pending.
     *
     * A single nullable slot (not keyed by crate id) is safe only because [activeCrate] is
     * capped at one crate at a time — [tryFireAtCrate] already refuses to re-target whatever
     * crate this field currently names, so there's never more than one commitment to track. If
     * that single-active-crate cap is ever lifted, this must become keyed by crate id (e.g. a
     * `Map<Long, PendingCrateHit>`), or firing at a second crate while the first's hit is still
     * pending would silently overwrite this slot and drop the first crate's resolution — the
     * exact class of bug this field exists to prevent, just reopened for multi-crate concurrency. */
    var pendingCrateHit by mutableStateOf<PendingCrateHit?>(null)
        private set

    /** The opponent's live sniper aim-dot offset, recomputed in *my own* frame every tick (see
     * [startTickers]) — null whenever the opponent isn't currently aiming a sniper at me.
     * Approximately 0 when they're aimed exactly at me; see
     * GameMessage.MotionUpdate.sniperAimHeadingDegrees's doc for why this can't be a value
     * computed once on receipt (my own heading keeps changing the correct answer). */
    var sniperDotOffsetDegrees by mutableStateOf<Float?>(null)
        private set

    val isPracticeMode: Boolean = gameSession.isPracticeMode

    private val connection get() = activeGameConnection.current
    private val outcomeResolver = OutcomeResolver(GameConstants.DRAW_WINDOW_MS)

    private val myBaselineHeadingDegrees: Float = checkNotNull(gameSession.myBaselineHeadingDegrees) {
        "DuelViewModel requires calibration to have completed first"
    }
    private val initialOpponentPosition = BearingModel.computeInitialOpponentPosition(
        baselineHeadingDegrees = myBaselineHeadingDegrees,
        assumedDistanceMeters = GameConstants.ASSUMED_INITIAL_DISTANCE_METERS,
    )

    private var myHeadingDegrees = myBaselineHeadingDegrees
    private var myStepVector = Vector2.ZERO
    private var opponentStepVector = Vector2.ZERO
    private var myHealthZeroAtMs: Long? = null
    private var reloadStartedAtMs: Long? = null

    /** Built once when a reload starts (see [startReload]) rather than reconstructed every
     * reload-ticker tick — its 4 fields are fixed for the reload's whole lifetime, since
     * [equippedWeapon] can't change while one is in flight (a crate pickup cancels any
     * in-progress reload outright rather than letting it keep ticking under a new weapon). */
    private var activeReload: Reload? = null

    /** Set on the idle->dragging transition while the bow is equipped; cleared on every
     * release/no-op resolution so a release-then-immediately-redraw sequence always starts a
     * fresh charge timer rather than inheriting a prior draw's timing. */
    private var aimDragStartedAtMs: Long? = null

    private var isLaserBeamActive = false
    private var laserBeamJob: Job? = null

    /** The shooter's last-broadcast absolute aim heading, or null while they aren't aiming a
     * sniper at me — an absolute (magnetic-frame) value, never a screen-relative one; see
     * GameMessage.MotionUpdate.sniperAimHeadingDegrees's doc. */
    private var opponentAimHeadingDegrees: Float? = null

    private var nextCrateId = 0L

    init {
        motionProvider.headingUpdates()
            .onEach { sample ->
                myHeadingDegrees = sample.headingDegrees
                if (!uiState.showAccuracyWarning && isAccuracyLow(sample.accuracy)) {
                    uiState = uiState.copy(showAccuracyWarning = true)
                }
            }
            .launchIn(viewModelScope)

        motionProvider.stepEvents()
            .onEach { myStepVector += BearingModel.stepDelta(myHeadingDegrees, GameConstants.STEP_LENGTH_METERS) }
            .launchIn(viewModelScope)

        motionProvider.tiltUpdates()
            .onEach { sample -> deviceTilt = Offset(sample.xAxis, sample.yAxis) }
            .launchIn(viewModelScope)

        connection.incomingMessages
            .onEach { message -> handleIncoming(message) }
            .launchIn(viewModelScope)

        connection.sessionEndedEvents()
            .onEach { sessionEnded = true }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            connection.sendMessage(GameMessage.HealthUpdate(GameConstants.MAX_PLAYER_HEALTH))
        }

        startMotionBroadcastLoop()
        startTickers()
        // Always on in a real match; in Solo Test Mode, gated by the main menu's "Weapon
        // crates" toggle so a practice session can be tested without crate interruptions.
        if (gameSession.cratesEnabledForDuel) startCrateSpawnLoop()
    }

    private fun startMotionBroadcastLoop() {
        viewModelScope.launch {
            while (true) {
                val drag = aimDrag
                val sniperAimHeading = if (uiState.equippedWeapon == WeaponType.SNIPER && drag != null && canFire()) {
                    val aimAngle = HitTest.angleFromDrag(drag.x, drag.y)
                    AngleMath.normalizeSigned(myHeadingDegrees + aimAngle)
                } else {
                    null
                }
                connection.sendMessage(
                    GameMessage.MotionUpdate(myHeadingDegrees, myStepVector.x, myStepVector.y, sniperAimHeading),
                )
                delay(GameConstants.HEADING_BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * A single consolidated ticker driving every ~20Hz-while-relevant derived value: reload
     * progress, the bow's charge progress, and the sniper laser dot's offset. Originally three
     * separate `while(true)+delay` loops — merged into one during review, since each individually
     * idle-polls at the same cadence when its own condition isn't active, so three of them
     * running concurrently for the whole match (even when e.g. no sniper is ever aimed at you)
     * was three times the coroutine wakeups for no behavior difference.
     *
     * This consolidation alone does **not** fix the separate bow-charge ticker's original
     * first-frame glitch (charge ring jumping straight to ~40% instead of animating from 0%) —
     * an idle→active transition can still land mid-sleep on this ticker's own up-to-250ms idle
     * interval before the next wake-up recomputes progress from the real elapsed time. Found
     * during a second review pass: fixing that requires a *synchronous* reset at the exact
     * moment charging/reloading starts, not a faster poll — see the `bowChargeProgress = 0f` in
     * [onAimDrag]'s BOW branch and `reloadProgress = 0f` in [startReload], both of which guarantee
     * the very first rendered frame is 0% regardless of when this ticker next wakes. This ticker
     * only needs to keep those values moving forward afterward, not originate their first frame.
     */
    private fun startTickers() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                var anyActive = false

                val reloadStarted = reloadStartedAtMs
                if (reloadStarted != null) {
                    anyActive = true
                    val reload = activeReload ?: currentReload().also { activeReload = it }
                    reloadProgress = reload.progressFraction(reloadStarted, now)
                    isInPerfectReloadZone = reload.isInPerfectZone(reloadStarted, now)
                    if (reload.isComplete(reloadStarted, now)) completeReload()
                }

                val chargeStarted = aimDragStartedAtMs
                if (chargeStarted != null && uiState.equippedWeapon == WeaponType.BOW) {
                    anyActive = true
                    val holdMs = uiState.equippedWeaponConfig.bowMinChargeHoldMs
                    val elapsed = now - chargeStarted
                    bowChargeProgress = if (holdMs <= 0L) 1f else (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)
                } else {
                    bowChargeProgress = 0f
                }

                val aimHeading = opponentAimHeadingDegrees
                if (aimHeading != null) {
                    anyActive = true
                    val bearingToShooter = BearingModel.computeBearingToOpponentOnScreen(
                        initialOpponentPosition = initialOpponentPosition,
                        opponentStepVector = opponentStepVector,
                        myStepVector = myStepVector,
                        myCurrentHeadingDegrees = myHeadingDegrees,
                    )
                    sniperDotOffsetDegrees = BearingModel.sniperDotOffsetDegrees(
                        shooterAbsoluteAimHeadingDegrees = aimHeading,
                        bearingToShooterScreenRelativeDegrees = bearingToShooter,
                        myCurrentHeadingDegrees = myHeadingDegrees,
                    )
                } else {
                    sniperDotOffsetDegrees = null
                }

                delay(
                    if (anyActive) GameConstants.RELOAD_RING_POLL_INTERVAL_MS else GameConstants.RELOAD_TICKER_IDLE_POLL_INTERVAL_MS,
                )
            }
        }
    }

    private fun startCrateSpawnLoop() {
        viewModelScope.launch {
            while (true) {
                delay(
                    Random.nextLong(GameConstants.CRATE_SPAWN_MIN_INTERVAL_MS, GameConstants.CRATE_SPAWN_MAX_INTERVAL_MS),
                )
                if (activeCrate == null) spawnCrate()
            }
        }
    }

    private fun spawnCrate() {
        val weaponType = WeaponType.entries.filterNot { it == WeaponType.PISTOL }.random()
        val laneXDp = Random.nextFloat() * 2f * GameConstants.CRATE_LANE_MARGIN_DP - GameConstants.CRATE_LANE_MARGIN_DP
        activeCrate = FallingCrate(
            id = nextCrateId++,
            weaponType = weaponType,
            spawnedAtMs = System.currentTimeMillis(),
            laneXDp = laneXDp,
            fallDurationMs = GameConstants.CRATE_FALL_DURATION_MS,
        )
    }

    /** Called by DuelScreen once a crate's fall animation reaches progress 1 without having been
     * shot down — frees the spawn slot the same way a successful pickup does, so the single-
     * active-crate cap doesn't permanently deadlock the first time a player simply misses one.
     * Guarded by id so a crate already cleared by [triggerCrateExplosion] mid-fall isn't
     * double-cleared. */
    fun onCrateLanded(crateId: Long) {
        if (activeCrate?.id == crateId) activeCrate = null
    }

    /** The crate's current dp offset from center, or null once it's already landed — shared by
     * every fire path's crate ray-hit check ([fireInstant]/[fireBow]) so they never disagree
     * with the renderer about where a crate visually is (position math lives in [CrateMath]). */
    private fun currentCrateOffsetDp(crate: FallingCrate): Vector2? {
        val progress = CrateMath.fallProgress(crate.spawnedAtMs, System.currentTimeMillis(), crate.fallDurationMs)
        if (CrateMath.hasLanded(progress)) return null
        return CrateMath.currentOffsetDp(
            progress = progress,
            laneXDp = crate.laneXDp,
            topYDp = GameConstants.CRATE_SPAWN_TOP_Y_DP,
            bottomYDp = GameConstants.CRATE_LAND_BOTTOM_Y_DP,
            wobbleAmplitudeDp = GameConstants.CRATE_WOBBLE_AMPLITUDE_DP,
            wobbleFrequency = GameConstants.CRATE_WOBBLE_FREQUENCY,
        )
    }

    /** Called synchronously from [fireAtCrate] the instant a crate hit is confirmed — cancels any
     * in-progress reload outright (deliberately generous, same reasoning the old release-to-grab
     * pickup used: crates are visible and time-limited, so locking pickup behind a finished
     * reload would make many unreachable by design) and switches weapons immediately at a full
     * magazine.
     *
     * Deliberately **not** delayed to the crate's visual destruction the way [triggerCrateExplosion]
     * is: this used to be delayed too (both bundled into one `resolveCrateHit`), but that left a
     * window — between firing at the crate and the projectile visually arriving — during which an
     * unrelated later shot could start its own reload, only for the crate shot's now-delayed
     * weapon-switch to silently clobber that unrelated reload out from under it once it finally
     * ran. Calling this with zero delay, back-to-back with [spendRound] in the same synchronous
     * call, closes that window entirely: there is no gap for another shot's state to exist in
     * before this one lands. */
    private fun equipWeaponFromCrate(crate: FallingCrate) {
        reloadStartedAtMs = null
        activeReload = null
        reloadProgress = 0f
        isInPerfectReloadZone = false
        uiState = uiState.copy(
            equippedWeapon = crate.weaponType,
            roundsRemaining = WeaponConfigs.forType(crate.weaponType).magazineSize,
        )
    }

    /** Called once the crate-seeking projectile's travel time elapses (see [fireAtCrate]) — purely
     * the crate's *visual* destruction (clearing [activeCrate]/[pendingCrateHit] and triggering the
     * explosion effect: rings/particles/shake/flash, see DuelScreen) at its last known position.
     * The gameplay effect of a crate hit (weapon switch) already happened immediately at fire time
     * via [equipWeaponFromCrate] — this function never touches ammo/reload/equippedWeapon, so it
     * can never clobber whatever the player has done with their weapon in the meantime. Guarded by
     * the caller against a crate that already landed/cleared mid-flight. */
    private fun triggerCrateExplosion(crate: FallingCrate, offsetDp: Vector2) {
        activeCrate = null
        pendingCrateHit = null
        uiState = uiState.copy(crateExplosionFlash = true)
        crateExplosion = CrateExplosionEffect(System.currentTimeMillis(), offsetDp)
        viewModelScope.launch {
            delay(GameConstants.CRATE_EXPLOSION_FLASH_DURATION_MS)
            uiState = uiState.copy(crateExplosionFlash = false)
        }
    }

    /** Called by DuelScreen once a destroyed crate's explosion animation finishes — same
     * guarded-by-timestamp clearing pattern as [onCrateLanded]/[onProjectileAnimationFinished]. */
    fun onCrateExplosionAnimationFinished(triggeredAtMs: Long) {
        if (crateExplosion?.triggeredAtMs == triggeredAtMs) crateExplosion = null
    }

    private fun currentReload(): Reload {
        val config = uiState.equippedWeaponConfig
        return Reload(
            durationMs = config.reloadDurationMs,
            perfectZoneStartFraction = config.reloadPerfectZoneStartFraction,
            perfectZoneEndFraction = config.reloadPerfectZoneEndFraction,
            missPenaltyMs = config.reloadMissPenaltyMs,
        )
    }

    /** Starts a reload, discarding any rounds still in the magazine — shared by the automatic
     * (magazine emptied by firing) and voluntary ([onManualReloadRequested]) trigger paths.
     * Resets [reloadProgress] synchronously (not left for [startTickers] to notice on its next
     * wake) so the reload ring's first rendered frame is guaranteed 0%, not whatever stale value
     * was left from a previous reload. */
    private fun startReload(now: Long) {
        reloadStartedAtMs = now
        activeReload = currentReload()
        reloadProgress = 0f
        isInPerfectReloadZone = false
        uiState = uiState.copy(roundsRemaining = 0)
    }

    /** Spends one round of the equipped magazine, auto-starting a reload if that empties it —
     * shared by every shot's tail, whether it lands on the opponent ([commitShot]) or on a crate
     * ([fireAtCrate]), so the two paths' ammo/reload bookkeeping can't drift apart. */
    private fun spendRound(now: Long) {
        val roundsAfterFiring = uiState.roundsRemaining - 1
        uiState = uiState.copy(roundsRemaining = roundsAfterFiring)
        if (roundsAfterFiring <= 0) startReload(now)
    }

    private fun completeReload() {
        if (reloadStartedAtMs == null) return
        reloadStartedAtMs = null
        activeReload = null
        reloadProgress = 0f
        isInPerfectReloadZone = false
        // Every weapon's reload — the pistol's own natural refill, or a crate weapon's single
        // magazine running dry (whether emptied by firing or forfeited early via a manual tap)
        // — always lands on the pistol. Crate weapons are never refilled; only the pistol is a
        // persistent default. One code path handles both cases; no `if (weapon != PISTOL)` branch.
        uiState = uiState.copy(
            equippedWeapon = WeaponType.PISTOL,
            roundsRemaining = WeaponConfigs.PISTOL.magazineSize,
        )
        reloadCompletedAtMs = System.currentTimeMillis()
    }

    /** Voluntary early reload — lets the player top off before the magazine empties, forfeiting
     * whatever rounds remain (same discard-on-reload convention as the automatic trigger). On a
     * crate weapon this is an intentional, consistent extension of the same affordance: it
     * forfeits the rest of that weapon's single magazine and reverts to the pistol, exactly like
     * letting the magazine run dry naturally would. */
    fun onManualReloadRequested() {
        if (reloadStartedAtMs != null) return
        if (uiState.roundsRemaining >= uiState.equippedWeaponConfig.magazineSize) return
        startReload(System.currentTimeMillis())
    }

    /** A tap on the reload ring: completes the reload instantly if the dial is in the perfect
     * zone, otherwise extends the remaining reload as a mistiming penalty. */
    fun onReloadRingTapped() {
        val startedAt = reloadStartedAtMs ?: return
        val now = System.currentTimeMillis()
        val reload = activeReload ?: currentReload()
        if (reload.isInPerfectZone(startedAt, now)) {
            completeReload()
        } else {
            reloadStartedAtMs = reload.penalizedStartTime(startedAt, now)
            reloadMissTapAtMs = now
        }
    }

    private fun handleIncoming(message: GameMessage) {
        when (message) {
            is GameMessage.MotionUpdate -> {
                opponentStepVector = Vector2(message.stepVectorXMeters, message.stepVectorYMeters)
                opponentAimHeadingDegrees = message.sniperAimHeadingDegrees
            }
            is GameMessage.FireEvent -> onFireReceived(message)
            is GameMessage.HealthUpdate -> {
                val clamped = message.remainingHealth.coerceIn(0, GameConstants.MAX_PLAYER_HEALTH)
                uiState = uiState.copy(opponentHealth = clamped)
                refreshOutcome()
            }
            else -> Unit
        }
    }

    private fun onFireReceived(fireEvent: GameMessage.FireEvent) {
        val config = WeaponConfigs.forType(fireEvent.weaponType)
        // Reuse our own live bearing estimate as a proxy for "which way the shot came from" —
        // the receiver has no ground truth for the shooter's aim, only its own belief of
        // where the opponent currently is.
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initialOpponentPosition,
            opponentStepVector = opponentStepVector,
            myStepVector = myStepVector,
            myCurrentHeadingDegrees = myHeadingDegrees,
        )
        // travelDurationMs is peer-supplied cosmetic timing only (never consulted by
        // health/outcome math below) but still clamped on receipt like every other
        // peer-supplied number, since it feeds an Int-truncating tween() and a delay().
        val travelDurationMs = fireEvent.travelDurationMs.coerceIn(0L, GameConstants.MAX_PROJECTILE_TRAVEL_DURATION_MS)

        // Loud weapons always telegraph an incoming shot, hit or miss (today's existing
        // behavior, preserved as a no-regression baseline); silent weapons give zero warning
        // unless they actually land a hit.
        if (fireEvent.hit || config.isLoud) {
            incomingShot = IncomingShot(bearing, fireEvent.hit, System.currentTimeMillis(), fireEvent.weaponType, travelDurationMs)
        }
        if (!fireEvent.hit) return

        // Damage is always a fixed local lookup, never a peer-supplied value — a modified
        // client reporting an inflated FireEvent can't heal or over-damage either side.
        val newHealth = HealthMath.applyDamage(uiState.myHealth, config.damage, GameConstants.MAX_PLAYER_HEALTH)
        if (newHealth == 0 && myHealthZeroAtMs == null) {
            myHealthZeroAtMs = System.currentTimeMillis()
        }
        uiState = uiState.copy(myHealth = newHealth)
        viewModelScope.launch { connection.sendMessage(GameMessage.HealthUpdate(newHealth)) }
        refreshOutcome()

        viewModelScope.launch {
            // Delay the cosmetic flash/shake/haptic until the incoming projectile visually
            // reaches the character, instead of firing them the instant the network message
            // lands — health/outcome resolution above stays immediate and unaffected.
            delay(travelDurationMs)
            lastHitAtMs = System.currentTimeMillis()
            haptics.hitFeedback()
            uiState = uiState.copy(damageFlash = true, damageDirectionDegrees = bearing)
            delay(GameConstants.DAMAGE_FLASH_DURATION_MS)
            uiState = uiState.copy(damageFlash = false)
        }
    }

    private fun refreshOutcome() {
        val outcome = outcomeResolver.resolve(
            myHealth = uiState.myHealth,
            opponentHealth = uiState.opponentHealth,
            myHealthZeroAtMs = myHealthZeroAtMs,
            nowMs = System.currentTimeMillis(),
        )
        if (outcome == uiState.outcome) return
        uiState = uiState.copy(outcome = outcome)
        if (outcome != DuelOutcome.Ongoing) {
            gameSession.recordOutcome(outcome, uiState.myHealth, uiState.opponentHealth)
        }
    }

    fun onDismissAccuracyWarning() {
        uiState = uiState.copy(showAccuracyWarning = false)
    }

    fun onDismissAlignmentWarning() {
        uiState = uiState.copy(showAlignmentWarning = false)
    }

    // No per-shot cooldown — the reload (see startReload above) is now the only pacing break
    // between shots, so firing is gated solely on not currently being in one.
    private fun canFire(): Boolean = !uiState.isReloading

    fun onAimDrag(offset: Offset) {
        val wasIdle = aimDrag == null
        // Always updates, regardless of ammo/reload state: the aim line renders even while out
        // of ammo (dimmed in DuelScreen — see its own doc) and crate targeting must keep working
        // mid-reload (a crate landing during the ~3.5s reload window is routine, not an edge
        // case). Only the *side effects* of a fresh drag below are gated by canFire().
        aimDrag = offset

        if (uiState.equippedWeapon == WeaponType.LASER && isLaserBeamActive) return // already sweeping
        if (!canFire()) return

        if (wasIdle) {
            when (uiState.equippedWeaponConfig.type) {
                // bowChargeProgress reset synchronously here (not left for startTickers to
                // notice on its next wake, up to 250ms later) so the charge ring's first
                // rendered frame is guaranteed 0%, not a jump to whatever partial progress that
                // elapsed idle-poll delay would otherwise produce.
                WeaponType.BOW -> {
                    aimDragStartedAtMs = System.currentTimeMillis()
                    bowChargeProgress = 0f
                }
                WeaponType.LASER -> startLaserBeam()
                else -> Unit
            }
        }
    }

    /** Touching down with the laser equipped immediately commits its single charge and starts
     * the 1-second sweep — there is deliberately no cancel-without-spending path (high
     * risk/reward, matching a 1-shot weapon's tension). Continued dragging (handled in
     * [onAimDrag] above, which bypasses the ammo gate while [isLaserBeamActive]) live-updates
     * the beam's angle for the rest of the window. */
    private fun startLaserBeam() {
        val config = uiState.equippedWeaponConfig
        isLaserBeamActive = true
        // Ammo empties immediately for correct HUD feedback, but the Reload countdown itself is
        // deliberately not started until the beam concludes (in finishLaserBeam below) —
        // decoupling the two clocks so LASER_BEAM_DURATION_MS/LASER_RELOAD_DURATION_MS can never
        // interact badly regardless of how either is retuned later.
        uiState = uiState.copy(roundsRemaining = 0)
        weaponSoundPlayer.playFireSound(WeaponType.LASER)
        laserBeamJob = viewModelScope.launch {
            val accumulator = LaserBeamHitAccumulator()
            val endAt = System.currentTimeMillis() + config.laserBeamDurationMs
            try {
                while (System.currentTimeMillis() < endAt) {
                    val drag = aimDrag ?: break // player released — exit, let finally send the result
                    val (angle, bearing) = currentAimAndBearing(drag)
                    laserBeamAngleDegrees = angle
                    accumulator.sample(angle, bearing, config.hitToleranceDegrees)
                    delay(config.laserSampleIntervalMs)
                }
            } finally {
                finishLaserBeam(accumulator)
            }
        }
    }

    /** Runs whether the beam's sampling loop finished naturally or was cancelled by an early
     * release ([onAimRelease]) — guarantees exactly one FireEvent is sent and the post-beam
     * reload starts, regardless of exit path. Kept as its own named suspend fun rather than
     * inlined into [startLaserBeam]'s `finally` block: a `NonCancellable` suspend call inside a
     * `finally` that also does more work afterward is exactly the kind of nesting that's easy to
     * get subtly wrong (see the `NonCancellable` note below) if spread out inline. */
    private suspend fun finishLaserBeam(accumulator: LaserBeamHitAccumulator) {
        isLaserBeamActive = false
        laserBeamAngleDegrees = null
        // sendMessage is a suspend fun with a real suspension point; calling it after the
        // enclosing job was just cancel()-ed throws immediately unless wrapped in
        // NonCancellable — without this, a beam ended by early release (the most common way to
        // end it) would silently never tell the opponent about a landed hit.
        withContext(NonCancellable) {
            connection.sendMessage(
                GameMessage.FireEvent(
                    hit = accumulator.registeredHit,
                    weaponType = WeaponType.LASER,
                    travelDurationMs = GameConstants.LASER_INCOMING_TRAVEL_DURATION_MS,
                ),
            )
        }
        if (accumulator.registeredHit) hitConfirmedAtMs = System.currentTimeMillis()
        startReload(System.currentTimeMillis())
    }

    /**
     * [densityPxPerDp] (pixels-per-dp, i.e. `with(density) { 1.dp.toPx() }`) is used only by the
     * bow's pull-distance-to-speed calculation; every other weapon ignores it. Passed in from
     * DuelScreen (which has Density available inside its pointerInput block) rather than
     * computed here, since this ViewModel has no Compose/Android dependency of its own.
     *
     * Release is checked in a fixed priority order: (1) an active laser beam always just ends
     * the beam — see [startLaserBeam]'s doc for why it never interacts with crates either; (2)
     * otherwise, firing proceeds only if ammo allows it. A crate is no longer picked up by simply
     * releasing near it — [fireInstant]/[fireBow] each check whether the fired shot's own aim ray
     * actually hits the crate (see [currentCrateOffsetDp]/[CrateMath.isRayHit]), so destroying a
     * crate now spends a real shot exactly like firing at the opponent would.
     */
    fun onAimRelease(densityPxPerDp: Float) {
        val drag = aimDrag
        aimDrag = null

        if (uiState.equippedWeapon == WeaponType.LASER && isLaserBeamActive) {
            laserBeamJob?.cancel() // triggers finishLaserBeam() above -> sends exactly one FireEvent
            return
        }

        if (!canFire()) return
        val fireDrag = drag ?: return // no recorded drag (e.g. a cancel with no prior start) is a no-op

        when (uiState.equippedWeapon) {
            WeaponType.BOW -> fireBow(fireDrag, densityPxPerDp * GameConstants.AIM_MAX_DRAG_RADIUS_DP)
            else -> fireInstant(fireDrag)
        }
    }

    /** Shared by every fire path: the aim angle and this device's own live bearing estimate to
     * the opponent, from the same drag offset. Not memoized — bearing depends on continuously
     * changing heading/step-vector state, so each call (an instant shot, a bow release, or one
     * sample of the laser's sweep) deliberately recomputes it fresh. */
    private fun currentAimAndBearing(drag: Offset): Pair<Float, Float> {
        val aimAngle = HitTest.angleFromDrag(drag.x, drag.y)
        val bearing = BearingModel.computeBearingToOpponentOnScreen(
            initialOpponentPosition = initialOpponentPosition,
            opponentStepVector = opponentStepVector,
            myStepVector = myStepVector,
            myCurrentHeadingDegrees = myHeadingDegrees,
        )
        return aimAngle to bearing
    }

    private fun fireInstant(drag: Offset) {
        val config = uiState.equippedWeaponConfig
        val (aimAngle, bearing) = currentAimAndBearing(drag)

        if (tryFireAtCrate(aimAngle, config, config.projectileSpeedDpPerSec)) return

        val hit = HitTest.testHit(aimAngle, bearing, config.hitToleranceDegrees)
        commitShot(
            weaponType = config.type,
            aimAngleDegrees = aimAngle,
            hit = hit,
            travelDurationMs = GameConstants.PROJECTILE_TRAVEL_DURATION_MS,
            sendDelayMs = config.projectileOffscreenTravelTimeMs,
        )
    }

    /** Shared by [fireInstant]/[fireBow]: both need the exact same "does this shot's aim ray hit
     * the active crate" gate before falling back to the normal opponent hit test, differing only
     * in which speed feeds the crate-seeking projectile's travel duration (a flat per-weapon speed
     * vs. the bow's per-shot pull-distance speed). Kept as one shared helper so a future change to
     * crate-hit priority/logic can't silently drift between the two call sites. Also refuses to
     * re-target a crate that already has a shot committed to destroying it (see [pendingCrateHit])
     * — without this, a rapid double-tap could spend a second round on the same crate, with only
     * one of the two shots' arrivals actually able to resolve it. Returns true if it fired at the
     * crate (caller should stop and not also run the opponent hit test). */
    private fun tryFireAtCrate(aimAngleDegrees: Float, config: WeaponConfig, speedDpPerSec: Float): Boolean {
        val crate = activeCrate ?: return false
        if (pendingCrateHit?.crateId == crate.id) return false
        val crateOffsetDp = currentCrateOffsetDp(crate) ?: return false
        if (!CrateMath.isRayHit(aimAngleDegrees, crateOffsetDp, GameConstants.CRATE_HIT_RADIUS_DP)) return false
        fireAtCrate(crate, aimAngleDegrees, config, crateOffsetDp, speedDpPerSec)
        return true
    }

    /** Fired instead of [commitShot] when [tryFireAtCrate] finds the shot's own aim ray hits the
     * active crate: spends ammo on the weapon that fired, then immediately swaps to the crate's
     * weapon ([equipWeaponFromCrate]) — no FireEvent is sent, since this shot was never aimed at
     * the opponent and has nothing to tell them.
     *
     * Two effects, two different timings: the *gameplay* effect (ammo spend + weapon swap) is
     * immediate/synchronous, so there's no window for another shot's own reload/weapon state to
     * exist in between and get silently clobbered when this one lands (see
     * [equipWeaponFromCrate]'s doc for why that matters). The *visual* effect (the crate actually
     * disappearing in an explosion) is still delayed until [travelDurationMs] elapses, scheduled
     * here directly rather than from [onProjectileAnimationFinished] — mirroring
     * [scheduleHitConfirmation]'s own doc: [outgoingShot] holds at most one shot, and a rapid-fire
     * burst (no per-shot cooldown gates firing) can overwrite it with an unrelated shot before this
     * one's own DuelScreen animation finishes, which would otherwise silently drop the visual
     * resolution entirely. [pendingCrateHit] is the durable record of this commitment instead —
     * DuelScreen uses it (not [outgoingShot]) to freeze the crate's rendered position and to guard
     * [onCrateLanded] against racing it, so the falling crate can't visibly drift away from where
     * this shot is headed before the explosion lands, regardless of what else gets fired in the
     * meantime. */
    private fun fireAtCrate(
        crate: FallingCrate,
        aimAngleDegrees: Float,
        config: WeaponConfig,
        crateOffsetDp: Vector2,
        speedDpPerSec: Float,
    ) {
        val now = System.currentTimeMillis()
        val distanceDp = hypot(crateOffsetDp.x, crateOffsetDp.y)
        val travelDurationMs = ((distanceDp / speedDpPerSec) * 1000).toLong()
            .coerceAtLeast(GameConstants.CRATE_HIT_MIN_TRAVEL_DURATION_MS)

        outgoingShot = OutgoingShot.AtCrate(
            aimAngleDegrees = aimAngleDegrees,
            firedAtMs = now,
            weaponType = config.type,
            travelDurationMs = travelDurationMs,
            crateId = crate.id,
            offsetDp = crateOffsetDp,
        )
        weaponSoundPlayer.playFireSound(config.type)
        // Spend the round on the weapon that fired, then immediately equip the crate's weapon —
        // back-to-back with no suspension in between, so there is no window for another shot to
        // observe (or be clobbered by) an in-between state. See equipWeaponFromCrate's own doc for
        // why this can't be delayed to the crate's visual destruction the way it used to be.
        spendRound(now)
        equipWeaponFromCrate(crate)

        val targetCrateId = crate.id
        pendingCrateHit = PendingCrateHit(targetCrateId, crateOffsetDp)
        viewModelScope.launch {
            delay(travelDurationMs)
            // The crate may have already landed during this shot's brief flight if something
            // else cleared it unexpectedly — triggerCrateExplosion is skipped in that case, same
            // "decided at fire time, only revealed on arrival" pattern as the opponent hit test.
            // In practice this is fully defensive: DuelScreen's crate-landed guard checks this
            // same pendingCrateHit field specifically so it can never fire early for this crate.
            val stillActiveCrate = activeCrate
            if (stillActiveCrate?.id == targetCrateId) {
                triggerCrateExplosion(stillActiveCrate, crateOffsetDp)
            } else {
                pendingCrateHit = null
            }
        }
    }

    /** The bow's "winds up" (a minimum hold before firing is allowed — an early release is a
     * no-op, not a guaranteed miss) and "variable speed based on how far the user pulls" both
     * extend the existing drag lifecycle rather than replacing it. Speed/travel duration are
     * derived per-shot using the same distance/speed formula that produces the flat
     * PROJECTILE_TRAVEL_DURATION_MS constant for every other weapon, just evaluated per-shot —
     * and, critically, sent to the opponent on FireEvent.travelDurationMs so their incoming-
     * arrow animation and damage-flash timing match what actually happened, not a guess. */
    private fun fireBow(drag: Offset, maxDragRadiusPx: Float) {
        val config = uiState.equippedWeaponConfig
        val startedAt = aimDragStartedAtMs
        aimDragStartedAtMs = null
        val now = System.currentTimeMillis()
        if (startedAt == null || !BowMath.isFullyCharged(startedAt, now, config.bowMinChargeHoldMs)) return

        val pullFraction = BowMath.pullFraction(hypot(drag.x, drag.y), maxDragRadiusPx)
        val speed = BowMath.speedDpPerSec(pullFraction, config.bowMinSpeedDpPerSec, config.bowMaxSpeedDpPerSec)
        val (aimAngle, bearing) = currentAimAndBearing(drag)

        if (tryFireAtCrate(aimAngle, config, speed)) return

        val travelDurationMs = (GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP / speed * 1000).toLong()
        val hit = HitTest.testHit(aimAngle, bearing, config.hitToleranceDegrees)
        // A weak draw is slower end-to-end on the wire too, not just visually — travelDurationMs
        // doubles as both the visual travel time and the network send delay.
        commitShot(WeaponType.BOW, aimAngle, hit, travelDurationMs, sendDelayMs = travelDurationMs)
    }

    /** The shared tail of every instant/bow shot once its aim/hit/timing are known: sets
     * [outgoingShot], plays the fire sound, spends ammo (auto-reloading if the magazine just
     * emptied), and sends the delayed [GameMessage.FireEvent]. Not used by the laser, whose
     * trigger moment/network timing genuinely differ (see [startLaserBeam]/[finishLaserBeam]). */
    private fun commitShot(
        weaponType: WeaponType,
        aimAngleDegrees: Float,
        hit: Boolean,
        travelDurationMs: Long,
        sendDelayMs: Long,
    ) {
        val now = System.currentTimeMillis()
        outgoingShot = OutgoingShot.AtOpponent(
            aimAngleDegrees = aimAngleDegrees,
            firedAtMs = now,
            weaponType = weaponType,
            travelDurationMs = travelDurationMs,
            wasHit = hit,
        )
        weaponSoundPlayer.playFireSound(weaponType)
        spendRound(now)

        viewModelScope.launch {
            // Flat delay standing in for projectile flight time (see GameConstants doc) — the
            // opponent doesn't learn the outcome until this "travel time" elapses.
            delay(sendDelayMs)
            connection.sendMessage(
                GameMessage.FireEvent(hit = hit, weaponType = weaponType, travelDurationMs = travelDurationMs),
            )
        }

        if (hit) scheduleHitConfirmation(travelDurationMs)
    }

    private fun scheduleHitConfirmation(travelDurationMs: Long) {
        viewModelScope.launch {
            // Scheduled from fire time, independent of outgoingShot's own animation slot — see
            // hitConfirmedAtMs's doc for why (a rapid-fire burst can overwrite that slot).
            delay(travelDurationMs)
            hitConfirmedAtMs = System.currentTimeMillis()
        }
    }

    /** Purely visual: frees [outgoingShot]'s animation slot. A crate-seeking shot's actual
     * resolution is scheduled independently in [fireAtCrate] (see [pendingCrateHit]'s doc for
     * why), not from here — this callback firing or not firing for a given shot has no bearing on
     * whether that shot's crate hit lands. */
    fun onProjectileAnimationFinished(firedAtMs: Long) {
        val shot = outgoingShot ?: return
        if (shot.firedAtMs != firedAtMs) return
        outgoingShot = null
    }

    fun onIncomingProjectileAnimationFinished(receivedAtMs: Long) {
        if (incomingShot?.receivedAtMs == receivedAtMs) {
            incomingShot = null
        }
    }

    /** [quit] disconnects, which flips [sessionEnded] via the [sessionEndedEvents] collector
     * in [init] — DuelScreen already treats that the same as any other lost connection. */
    fun onLeavePracticeClicked() {
        viewModelScope.launch { connection.quit() }
    }
}
