package com.GoogleLLC.TrivialAdventure.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed dark "vault" scheme derived from the launcher icon.
 *
 * Dynamic colour is deliberately NOT used: the whole point of this pass is that the in-game art
 * matches the icon, and Material You would recolour it per-wallpaper and break that link.
 */
private val AdventureColorScheme = darkColorScheme(
    primary = RuneGold,
    onPrimary = VaultNight,
    primaryContainer = VaultPanel,
    onPrimaryContainer = RuneGold,

    secondary = XpMint,
    onSecondary = VaultNight,

    tertiary = GripBrown,
    onTertiary = BladeSilver,

    background = VaultNight,
    onBackground = BladeSilver,

    surface = VaultPanel,
    onSurface = BladeSilver,
    surfaceVariant = VaultPanel,
    onSurfaceVariant = MutedLavender,

    error = EmberRed,
    onError = Color.White,

    outline = MutedLavender,
)

@Composable
fun TrivialAdventureTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AdventureColorScheme,
        typography = Typography,
        content = content,
    )
}
