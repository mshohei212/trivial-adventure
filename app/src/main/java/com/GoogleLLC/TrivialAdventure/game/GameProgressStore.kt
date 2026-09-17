package com.GoogleLLC.TrivialAdventure.game

import android.content.Context
import androidx.core.content.edit

/**
 * Persists level/XP across process death.
 *
 * This matters for TBYB testing: the paywall takes the user out to Play and the game process
 * is frequently killed while it is backgrounded. Without persistence the tester would come
 * back to Level 1 and have to grind to the milestone again on every single run.
 */
class GameProgressStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Pair<Int, Int> {
        val level = prefs.getInt(KEY_LEVEL, 1).coerceAtLeast(1)
        val xp = prefs.getInt(KEY_XP, 0).coerceIn(0, GameRules.XP_PER_LEVEL - 1)
        return level to xp
    }

    fun save(level: Int, xp: Int) {
        prefs.edit {
            putInt(KEY_LEVEL, level)
            putInt(KEY_XP, xp)
        }
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val PREFS_NAME = "trivial_adventure_progress"
        const val KEY_LEVEL = "level"
        const val KEY_XP = "xp"
    }
}
