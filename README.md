# TrivialAdventure

A deliberately minimal Kotlin/Compose "RPG" whose only real job is to reach a **progression
milestone** quickly so the Google Play **Game Trials (Try Before You Buy) Progression API** can
be exercised end to end.

- **Package / applicationId:** `com.GoogleLLC.TrivialAdventure` (debug builds install as
  `com.GoogleLLC.TrivialAdventure.debug`)
- **Milestone:** reaching **Level 5** and tapping **Fight Boss** ends the trial
- **Stack:** Kotlin, Jetpack Compose (Material 3), MVVM, Gradle KTS + version catalog

---

## The milestone trigger

```
Level 1 ──quest × ~14──▶ Level 5 (first boss)
                            │  tap "Fight Boss"
                            ▼
                   MainViewModel.fightBoss()
                            │  level == TRIAL_MILESTONE_LEVEL
                            ▼
              GameEffect.RequestStopTrial  (one-shot effect)
                            │
                            ▼
              MainActivity  →  GameTrialsManager.stopTrial(activity)
                            │
                            ▼
                    TrialOutcome  →  viewModel.onTrialOutcome(...)
```

`stopTrial` needs a foreground `Activity` (the paywall attaches to its window), and a ViewModel
must never hold one — hence the effect hop through the Activity.

### Outcomes

| Outcome | When | Game behaviour |
|---|---|---|
| `TrialStopped` | SDK present, trial active | **No reward.** Play raises the paywall; the player stays on Level 5 so returning resumes exactly there. |
| `AlreadyUnlocked` | User owns the full game | Toast *"Full Game Unlocked! Boss Defeated!"*, advance to Level 6 |
| `SdkUnavailable` | `play-trials-sdk.aar` not on the classpath | Same as above — the sandbox stays playable |
| `Failed(reason)` | SDK threw | Same as above (**fail open**) |

> [!IMPORTANT]
> Every outcome except `TrialStopped` fails open. A broken or absent trial integration must
> never soft-lock a tester behind a boss they cannot fight.

### The boss gate

`GameRules.applyXp` stops levelling the moment a boss level is reached, banking any surplus XP.
Without this, a lucky 40-XP roll could carry the player from Level 4 straight past Level 5 and
the paywall would never fire.

---

## Adding the SDK

The Trials SDK is partner-distributed and **not** published to Google Maven, so
`GameTrialsManager` binds to it **reflectively**:

1. Drop `play-trials-sdk.aar` into [`app/libs/`](app/libs/).
2. Sync. `app/build.gradle.kts` already includes `fileTree(... "*.aar" ...)`.
3. The status banner at the top of the screen flips from `SDK MISSING` to
   `TRIAL ACTIVE — PlayTrials.stopTrial(Activity) [static]`, showing the exact bound signature.

The resolver tries several known class/method shapes (static utility vs. singleton factory), so
minor drift between AAR drops does not break the build. Once the coordinate is public, delete
the reflection and call `PlayTrials.stopTrial(activity)` directly.

```bash
adb logcat -s GameTrialsManager   # prints the resolved binding on first use
```

### Manifest

```xml
<meta-data android:name="com.google.android.play.tbyb.trial_type" android:value="progression" />
```

plus a `<queries><package android:name="com.android.vending" /></queries>` entry, which is
required on Android 11+ for the SDK to reach the Play Store.

---

## QA controls

Bottom of the screen:

- **Reset sandbox** — back to Level 1 with the trial re-armed (no reinstall needed).
- **Simulate purchase / Re-lock** — forces the entitlement branch without a real transaction.

Progress is persisted, because the paywall hand-off frequently kills the game process and
re-grinding to Level 5 on every run is intolerable.

---

## Build

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Requires JDK 17–21 (AGP 8.7.3 / Gradle 8.9). If your default JDK is newer:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

## Key files

| File | Role |
|---|---|
| `trials/GameTrialsManager.kt` | Sole seam to the SDK; reflective binding, entitlement, `stopTrial` |
| `trials/TrialModels.kt` | `TrialAvailability` / `TrialOutcome` — SDK-free domain types |
| `MainViewModel.kt` | Level/XP state, quest & boss logic, milestone branch |
| `MainActivity.kt` | Hosts Compose; performs the `Activity`-scoped SDK call |
| `game/GameModels.kt` | `GameRules` (pure, unit-tested) + `GameUiState` |
| `ui/GameScreen.kt` | Compose UI, trial status banner, QA controls |
