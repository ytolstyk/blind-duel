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
) {
    // Derived, not stored: startReload()/completeReload() always empty/refill roundsRemaining
    // in the same call that starts/ends a reload, so this can never desync from it. Flips at
    // most twice per magazine cycle — well under the ~5Hz recomposition-isolation threshold that
    // applies to reloadProgress below.
    val isReloading: Boolean get() = roundsRemaining <= 0
    val equippedWeaponConfig: WeaponConfig get() = WeaponConfigs.forType(equippedWeapon)
}

/** A just-fired shot, for the shooter's own on-screen projectile animation. [wasHit] is already
 * known locally at fire time (the shooter decides hit/miss unilaterally). [travelDurationMs] is
 * per-shot (not a flat constant) so the bow's pull-distance-based speed animates correctly. This
 * slot holds at most one shot — with no per-shot cooldown, a fast rapid-fire burst can overwrite
 * it before its own animation finishes, which is why the "HIT" indicator (see
 * [DuelViewModel.hitConfirmedAtMs]) is scheduled independently from fire time rather than from
 * this animation completing, so a confirmed hit is never silently dropped just because a later
 * shot in the same burst replaced this slot first. */
data class OutgoingShot(
    val aimAngleDegrees: Float,
    val firedAtMs: Long,
    val wasHit: Boolean,
    val weaponType: WeaponType,
    val travelDurationMs: Long,
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
     * Guarded by id so a crate already cleared by [pickUpCrate] mid-fall isn't double-cleared. */
    fun onCrateLanded(crateId: Long) {
        if (activeCrate?.id == crateId) activeCrate = null
    }

    /**
     * [densityPxPerDp] converts the crate's logical dp position (and hit radius) into the same
     * px space [drag] is already in — a real two-point Euclidean check needs both points in one
     * consistent unit. An earlier version instead reconstructed an "aim point" by projecting the
     * drag's *angle* onto the crate's own radial distance, which reduces to a chord-length
     * formula and is still an angle-only test in disguise (see [CrateMath.isHit]'s doc for why
     * that's wrong) — found and fixed during review.
     */
    private fun findCrateHit(drag: Offset, densityPxPerDp: Float): FallingCrate? {
        val crate = activeCrate ?: return null
        val progress = CrateMath.fallProgress(crate.spawnedAtMs, System.currentTimeMillis(), crate.fallDurationMs)
        if (CrateMath.hasLanded(progress)) return null
        val crateOffsetDp = CrateMath.currentOffsetDp(
            progress = progress,
            laneXDp = crate.laneXDp,
            topYDp = GameConstants.CRATE_SPAWN_TOP_Y_DP,
            bottomYDp = GameConstants.CRATE_LAND_BOTTOM_Y_DP,
            wobbleAmplitudeDp = GameConstants.CRATE_WOBBLE_AMPLITUDE_DP,
            wobbleFrequency = GameConstants.CRATE_WOBBLE_FREQUENCY,
        )
        val crateOffsetPx = Vector2(crateOffsetDp.x * densityPxPerDp, crateOffsetDp.y * densityPxPerDp)
        val dragPointPx = Vector2(drag.x, drag.y)
        val hitRadiusPx = GameConstants.CRATE_HIT_RADIUS_DP * densityPxPerDp
        return crate.takeIf { CrateMath.isHit(dragPointPx, crateOffsetPx, hitRadiusPx) }
    }

    /** A pickup cancels any in-progress reload outright (deliberately generous — crates are
     * visible and time-limited, so locking pickup behind a finished reload would make many
     * unreachable by design) and switches weapons immediately at a full magazine. */
    private fun pickUpCrate(crate: FallingCrate) {
        activeCrate = null
        reloadStartedAtMs = null
        activeReload = null
        reloadProgress = 0f
        isInPerfectReloadZone = false
        uiState = uiState.copy(
            equippedWeapon = crate.weaponType,
            roundsRemaining = WeaponConfigs.forType(crate.weaponType).magazineSize,
        )
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
     * [densityPxPerDp] (pixels-per-dp, i.e. `with(density) { 1.dp.toPx() }`) is used by the
     * bow's pull-distance-to-speed calculation and by the crate hit-test's dp->px conversion;
     * every other weapon ignores it. Passed in from DuelScreen (which has Density available
     * inside its pointerInput block) rather than computed here, since this ViewModel has no
     * Compose/Android dependency of its own.
     *
     * Release is checked in a fixed priority order, each guard independent of the others: (1) an
     * active laser beam always just ends the beam — see [startLaserBeam]'s doc for why it can
     * never also pick up a crate; (2) a crate under the release point is always picked up, even
     * if the same release would also have hit the opponent or the weapon is still reloading —
     * crates are visible/time-limited, so they take priority over both; (3) otherwise, firing
     * proceeds only if ammo allows it.
     */
    fun onAimRelease(densityPxPerDp: Float) {
        val drag = aimDrag
        aimDrag = null

        if (uiState.equippedWeapon == WeaponType.LASER && isLaserBeamActive) {
            laserBeamJob?.cancel() // triggers finishLaserBeam() above -> sends exactly one FireEvent
            return
        }

        val crateHit = drag?.let { findCrateHit(it, densityPxPerDp) }
        if (crateHit != null) {
            pickUpCrate(crateHit)
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
        val hit = HitTest.testHit(aimAngle, bearing, config.hitToleranceDegrees)
        commitShot(
            weaponType = config.type,
            aimAngleDegrees = aimAngle,
            hit = hit,
            travelDurationMs = GameConstants.PROJECTILE_TRAVEL_DURATION_MS,
            sendDelayMs = config.projectileOffscreenTravelTimeMs,
        )
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
        val travelDurationMs = (GameConstants.PROJECTILE_VISUAL_TRAVEL_DISTANCE_DP / speed * 1000).toLong()

        val (aimAngle, bearing) = currentAimAndBearing(drag)
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
        outgoingShot = OutgoingShot(aimAngleDegrees, now, hit, weaponType, travelDurationMs)
        weaponSoundPlayer.playFireSound(weaponType)

        val roundsAfterFiring = uiState.roundsRemaining - 1
        uiState = uiState.copy(roundsRemaining = roundsAfterFiring)
        if (roundsAfterFiring <= 0) startReload(now)

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
