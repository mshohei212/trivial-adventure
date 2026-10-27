package com.GoogleLLC.TrivialAdventure.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.GoogleLLC.TrivialAdventure.R
import com.GoogleLLC.TrivialAdventure.game.Encounter
import com.GoogleLLC.TrivialAdventure.game.Enemy
import com.GoogleLLC.TrivialAdventure.game.GameRules
import com.GoogleLLC.TrivialAdventure.game.GameUiState
import com.GoogleLLC.TrivialAdventure.game.LogKind
import com.GoogleLLC.TrivialAdventure.game.QuestLogEntry
import com.GoogleLLC.TrivialAdventure.trials.TrialAvailability
import com.GoogleLLC.TrivialAdventure.ui.art.SceneStage
import com.GoogleLLC.TrivialAdventure.ui.theme.BossRed
import com.GoogleLLC.TrivialAdventure.ui.theme.EmberRed
import com.GoogleLLC.TrivialAdventure.ui.theme.LevelUpGold
import com.GoogleLLC.TrivialAdventure.ui.theme.QuestTextColor
import com.GoogleLLC.TrivialAdventure.ui.theme.RuneGold
import com.GoogleLLC.TrivialAdventure.ui.theme.SystemMint
import com.GoogleLLC.TrivialAdventure.ui.theme.TrialBlue
import com.GoogleLLC.TrivialAdventure.ui.theme.TrivialAdventureTheme
import com.GoogleLLC.TrivialAdventure.ui.theme.VaultDeep
import com.GoogleLLC.TrivialAdventure.ui.theme.VaultNight
import com.GoogleLLC.TrivialAdventure.ui.theme.XpMint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    state: GameUiState,
    onPrimaryAction: () -> Unit,
    onToggleUnlock: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = VaultNight,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The launcher icon's sword, reused verbatim as the app crest.
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                        Text("Trivial Adventure", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VaultNight,
                    titleContentColor = RuneGold,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusHeader(state)

            SceneStage(
                encounterKey = state.lastEncounter?.id ?: -1L,
                enemy = state.lastEncounter?.enemy,
                // A boss level shows the boss standing guard even before you tap.
                isBoss = state.isBossLevel || state.lastEncounter?.isBoss == true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )

            TrialStatusBanner(state.trialAvailability)

            PrimaryActionButton(state = state, onClick = onPrimaryAction)

            if (state.isTrialMilestone) {
                Text(
                    text = "This boss is the progression milestone. " +
                        "Tapping it calls stopTrial() and hands over to the Play paywall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BossRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(color = VaultDeep)

            QuestLog(
                entries = state.log,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            QaControls(state = state, onToggleUnlock = onToggleUnlock, onReset = onReset)
        }
    }
}

@Composable
private fun StatusHeader(state: GameUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Level ${state.level}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = RuneGold,
                )
                Text(
                    text = "${state.xp} / ${GameRules.XP_PER_LEVEL} XP",
                    style = MaterialTheme.typography.titleMedium,
                    color = XpMint,
                )
            }

            LinearProgressIndicator(
                progress = { state.xpProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = XpMint,
                trackColor = VaultDeep,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )

            Text(
                text = if (state.isBossLevel) {
                    "BOSS LEVEL - ${state.bossName} awaits."
                } else {
                    "Next boss at level ${nextBossLevel(state.level)}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.isBossLevel) BossRed else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (state.isBossLevel) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Surfaces the live SDK binding. This is the single most useful thing on screen when
 * diagnosing a TBYB integration: it tells you immediately whether the AAR was picked up,
 * which method was bound, and whether Play thinks the user owns the game.
 */
@Composable
private fun TrialStatusBanner(availability: TrialAvailability) {
    val (label, color) = when (availability.kind) {
        TrialAvailability.Kind.TRIAL_ACTIVE ->
            "TRIAL ACTIVE - PlayTrials.stopTrial(Context)" to TrialBlue
        TrialAvailability.Kind.FULL_VERSION ->
            "FULL VERSION - trial milestone disabled" to XpMint
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PrimaryActionButton(state: GameUiState, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !state.isResolvingTrial,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        colors = if (state.isBossLevel) {
            ButtonDefaults.buttonColors(containerColor = EmberRed, contentColor = Color.White)
        } else {
            // Theme primary is the icon's gold, with dark text on top.
            ButtonDefaults.buttonColors()
        },
    ) {
        if (state.isResolvingTrial) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(12.dp))
            Text("Contacting Play...", style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                text = state.primaryActionLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun QuestLog(entries: List<QuestLogEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text(
            text = "No adventures yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val listState = rememberLazyListState()
    val newestId = entries.first().id

    // Entries are prepended, and a LazyColumn keeps its viewport anchored to the item that was
    // already visible. Without this the newest line - including the trial/paywall messages,
    // which are the whole point of this sandbox - silently scrolls out of view.
    LaunchedEffect(newestId) {
        listState.animateScrollToItem(0)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(items = entries, key = { it.id }) { entry ->
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = entry.kind.color(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LogKind.color(): Color = when (this) {
    LogKind.QUEST -> QuestTextColor
    LogKind.LEVEL_UP -> LevelUpGold
    LogKind.BOSS -> BossRed
    LogKind.TRIAL -> TrialBlue
    LogKind.SYSTEM -> SystemMint
}

/**
 * Test-harness affordances. Without these, re-testing the paywall means uninstalling and
 * re-grinding to level 5 for every run.
 */
@Composable
private fun QaControls(
    state: GameUiState,
    onToggleUnlock: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val unlocked = state.trialAvailability.kind == TrialAvailability.Kind.FULL_VERSION
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onReset) { Text("Reset sandbox") }
        TextButton(onClick = { onToggleUnlock(!unlocked) }) {
            Text(if (unlocked) "Re-lock (simulate trial)" else "Simulate purchase")
        }
    }
}

private fun nextBossLevel(level: Int): Int =
    ((level / GameRules.BOSS_LEVEL_INTERVAL) + 1) * GameRules.BOSS_LEVEL_INTERVAL

// -------------------------------------------------------------------------------------------
// Previews
// -------------------------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF1B1035, name = "Quest level")
@Composable
private fun GameScreenQuestPreview() {
    TrivialAdventureTheme {
        GameScreen(
            state = GameUiState(
                level = 3,
                xp = 60,
                trialAvailability = TrialAvailability.trialActive(),
                lastEncounter = Encounter(id = 1, enemy = Enemy.SLIME, xpGained = 32),
                log = listOf(
                    QuestLogEntry(2, "Level up! You are now level 3", LogKind.LEVEL_UP),
                    QuestLogEntry(1, "Defeated a Slime, +32 XP", LogKind.QUEST),
                ),
            ),
            onPrimaryAction = {},
            onToggleUnlock = {},
            onReset = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1035, name = "Milestone boss")
@Composable
private fun GameScreenMilestonePreview() {
    TrivialAdventureTheme {
        GameScreen(
            state = GameUiState(
                level = 5,
                xp = 15,
                trialAvailability = TrialAvailability.trialActive(),
                log = listOf(
                    QuestLogEntry(1, "Level 5! A boss bars the way: Gatekeeper of the Vault", LogKind.LEVEL_UP),
                ),
            ),
            onPrimaryAction = {},
            onToggleUnlock = {},
            onReset = {},
        )
    }
}
