package com.GoogleLLC.TrivialAdventure.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that actually matters for TBYB testing: a player must always *land on*
 * the milestone level rather than levelling straight past it.
 */
class GameRulesTest {

    @Test
    fun `quest xp accumulates without levelling below the threshold`() {
        val result = GameRules.applyXp(level = 1, xp = 30, gained = 40)

        assertEquals(1, result.level)
        assertEquals(70, result.xp)
        assertTrue(result.levelUps.isEmpty())
    }

    @Test
    fun `crossing 100 xp levels up once and carries the remainder`() {
        val result = GameRules.applyXp(level = 1, xp = 80, gained = 35)

        assertEquals(2, result.level)
        assertEquals(15, result.xp)
        assertEquals(listOf(2), result.levelUps)
    }

    @Test
    fun `levelling stops on a boss level even with surplus xp`() {
        // 90 + 400 XP would otherwise reach level 5 with plenty to spare and keep going.
        val result = GameRules.applyXp(level = 4, xp = 90, gained = 400)

        assertEquals(5, result.level)
        assertEquals(listOf(5), result.levelUps)
        assertTrue("Boss level must gate progression", GameRules.isBossLevel(result.level))
    }

    @Test
    fun `the first boss level is the trial milestone`() {
        assertEquals(GameRules.BOSS_LEVEL_INTERVAL, GameRules.TRIAL_MILESTONE_LEVEL)
        assertTrue(GameRules.isBossLevel(GameRules.TRIAL_MILESTONE_LEVEL))
    }

    @Test
    fun `boss levels are every fifth level`() {
        assertTrue(GameRules.isBossLevel(5))
        assertTrue(GameRules.isBossLevel(10))
        assertTrue(GameRules.isBossLevel(15))
        listOf(1, 2, 3, 4, 6, 9, 11).forEach {
            assertTrue("Level $it must not be a boss level", !GameRules.isBossLevel(it))
        }
    }

    @Test
    fun `a full grind from level 1 always stops at the milestone`() {
        var level = 1
        var xp = 0
        // Worst case: maximum XP per quest, which is the most likely to overshoot.
        repeat(100) {
            if (GameRules.isBossLevel(level)) return@repeat
            val result = GameRules.applyXp(level, xp, GameRules.QUEST_XP_RANGE.last)
            level = result.level
            xp = result.xp
        }
        assertEquals(GameRules.TRIAL_MILESTONE_LEVEL, level)
    }
}
