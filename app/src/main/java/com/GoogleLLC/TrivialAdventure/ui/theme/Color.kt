package com.GoogleLLC.TrivialAdventure.ui.theme

import androidx.compose.ui.graphics.Color

// -------------------------------------------------------------------------------------------
// Palette lifted directly from the launcher icon (res/drawable/ic_launcher_foreground.xml and
// ic_launcher_background), so the in-game art and the home-screen icon read as one piece.
// -------------------------------------------------------------------------------------------

/** Icon background. Also the app window background, which kills the white launch flash. */
val VaultNight = Color(0xFF1B1035)

/** Darker shade for scene gradients. */
val VaultDeep = Color(0xFF0E0820)

/** Raised surfaces (cards, panels). */
val VaultPanel = Color(0xFF241848)

/** Icon crossguard / pommel gold. Primary accent. */
val RuneGold = Color(0xFFFFC857)

/** Icon XP spark. Used for XP, progress and "system" log lines. */
val XpMint = Color(0xFF6EE7B7)

/** Icon blade. Primary text colour. */
val BladeSilver = Color(0xFFF2E9FF)

/** Icon grip. */
val GripBrown = Color(0xFFB07A3C)

/** Boss / danger. */
val EmberRed = Color(0xFFC62828)

/** Brighter red that stays legible as text on the dark background. */
val EmberLight = Color(0xFFFF6B6B)

/** Secondary text. */
val MutedLavender = Color(0xFFB9A8E0)

/** Trial / Play-related log lines. */
val TrialSky = Color(0xFF7CC4FF)

// Semantic aliases used by the quest log, tuned for the dark theme.
val QuestTextColor = BladeSilver
val LevelUpGold = RuneGold
val BossRed = EmberLight
val TrialBlue = TrialSky
val SystemMint = XpMint
