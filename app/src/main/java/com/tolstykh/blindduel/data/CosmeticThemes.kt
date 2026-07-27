package com.tolstykh.blindduel.data

/** Cosmetic Settings-screen choice for the player's own outgoing projectile trail — a bare
 * identity enum (no Android/Compose types) so the data layer that persists it stays
 * framework-free, matching [AppPreferencesRepository]'s existing primitive-only settings. The
 * actual [androidx.compose.ui.graphics.Color] mapping lives in `ui/theme` as an extension
 * property, kept out of this layer entirely. */
enum class TrailTheme(val displayName: String) {
    VIOLET("Violet"),
    EMBER("Ember"),
    MINT("Mint"),
}

/** Cosmetic Settings-screen choice for the ambient particle backdrop — see [TrailTheme]'s doc
 * for why this stays a bare identity enum here. */
enum class ParticleTheme(val displayName: String) {
    CLASSIC("Classic"),
    DUSK("Dusk"),
    NEON("Neon"),
}
