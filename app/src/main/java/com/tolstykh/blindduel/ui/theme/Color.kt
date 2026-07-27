package com.tolstykh.blindduel.ui.theme

import androidx.compose.ui.graphics.Color
import com.tolstykh.blindduel.data.ParticleTheme
import com.tolstykh.blindduel.data.TrailTheme

// Shared across both themes — saturated enough to stay legible against either background.
val DuelAccentViolet = Color(0xFF7C6BFF)
val DuelAccentEmber = Color(0xFFFF6B57)
val DuelParticle = Color(0xFF9AA3FF)

// Projectile flame-tail gradients: hot head color fading toward a cooled, dimmer tail color.
val DuelAccentVioletDim = Color(0xFF352F66)
val DuelIncomingDanger = Color(0xFFFF5A4D)
val DuelIncomingDangerDim = Color(0xFF5C1C1C)

val DuelReloadPerfectZone = Color(0xFFFFC94D)

// Multi-weapon system: the laser's live sweeping beam, and the crate/pickup accent color.
val DuelLaserBeam = Color(0xFFFF3366)
val DuelCrateAccent = Color(0xFFFFD54F)

val DuelBackgroundDark = Color(0xFF0F1226)
val DuelSurfaceDark = Color(0xFF171B34)
val DuelOnBackgroundDark = Color(0xFFEDEBFF)

val DuelBackgroundLight = Color(0xFFF6F5FC)
val DuelSurfaceLight = Color(0xFFFFFFFF)
val DuelOnBackgroundLight = Color(0xFF1A1A2E)

/** Color mapping for [TrailTheme] — kept here (not on the enum itself) so the data layer that
 * persists the player's choice never needs to depend on Compose's [Color] type. Never applied to
 * the incoming (opponent's) shot, which always stays [DuelIncomingDanger]/[DuelIncomingDangerDim]
 * as a fixed, non-customizable danger signal. */
val TrailTheme.headColor: Color
    get() = when (this) {
        TrailTheme.VIOLET -> DuelAccentViolet
        TrailTheme.EMBER -> Color(0xFFFF8A5C)
        TrailTheme.MINT -> Color(0xFF4DFFB0)
    }

val TrailTheme.tailColor: Color
    get() = when (this) {
        TrailTheme.VIOLET -> DuelAccentVioletDim
        TrailTheme.EMBER -> Color(0xFF6B3320)
        TrailTheme.MINT -> Color(0xFF1A5C40)
    }

/** Color mapping for [ParticleTheme] — see [TrailTheme.headColor]'s doc for why this lives here
 * rather than on the enum. Used by [com.tolstykh.blindduel.ui.components.ParticleField]'s ambient
 * star/dust backdrop. */
val ParticleTheme.paletteColor: Color
    get() = when (this) {
        ParticleTheme.CLASSIC -> DuelParticle
        ParticleTheme.DUSK -> Color(0xFFC98BFF)
        ParticleTheme.NEON -> Color(0xFF4DFFE0)
    }
