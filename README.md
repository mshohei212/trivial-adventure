# TrivialAdventure

A deliberately minimal Kotlin/Compose "RPG" whose only real job is to reach a **progression
milestone** quickly so the Google Play **Game Trials (Try Before You Buy) Progression API** can
be exercised end to end.

- **Package / applicationId:** `com.GoogleLLC.TrivialAdventure` (debug builds install as
  `com.GoogleLLC.TrivialAdventure.debug`)
- **Milestone:** reaching **Level 5** and tapping **Fight Boss** ends the trial
- **Stack:** Java (Play Trials SDK layer) + Kotlin/Jetpack Compose (Material 3) UI, MVVM,
  Gradle KTS + version catalog

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

## Play Trials requirements checklist

### Application / SDK

| Requirement | Status |
|---|---|
| Published as an Android App Bundle (`.aab`); legacy APKs unsupported | ✅ `./gradlew :app:bundleRelease` |
| `minSdkVersion` ≥ 24 (for Play installer checks) | ✅ `minSdk = 24` |
| `play-trials-sdk.aar` in `libs/` **and** declared in `build.gradle.kts` | ⚠️ declaration ✅ (`fileTree`), **AAR not present** |
| `<meta-data android:name="com.google.android.play.tbyb.trial_type" android:value="progression" />` | ✅ verified in the release merged manifest |
| `stopTrial()` called on the main thread, not an isolated process | ✅ enforced at runtime in `GameTrialsManager.stopTrial` |

### Play Console / security

| Requirement | Status |
|---|---|
| Play App Signing enabled | ❌ currently a local self-generated key |
| **Installer checks (anti-tamper) enabled** — strict prerequisite | ❌ not configured |
| AAB uploaded to an Open or Internal testing track | ❌ |
| Testers opted in via the opt-in URL | ❌ |

> [!WARNING]
> **Until the AAR is present, the real SDK path has never executed.** Every run so far has
> exercised only the fail-open branch (`SDK MISSING`). The reflective binding is written against
> expected signatures, not observed ones — check `adb logcat -s GameTrialsManager` first thing
> after dropping the AAR in.

> [!IMPORTANT]
> Trial enforcement depends on **installer checks** being enabled in the Play Console and on the
> build being installed **by Play** from a test track the account has opted into. A sideloaded
> APK will not enforce the trial, so `stopTrial()` appearing to do nothing locally is expected.

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

## Release signing

An **unsigned APK cannot be installed on a device or uploaded to Play**, so release builds
read their signing credentials from `keystore.properties` at the repo root.

One-time setup:

```bash
# 1. Generate a keystore (skip if you already have one).
keytool -genkeypair -v \
  -keystore keystore/trivialadventure-release.jks \
  -alias trivialadventure -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TrivialAdventure Sandbox, O=Google LLC, C=JP"

# 2. Point the build at it.
cp keystore.properties.template keystore.properties
$EDITOR keystore.properties      # fill in storePassword / keyPassword

# 3. Build.
./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk   (signed, installable)

# For Play, build the bundle instead -- Play Trials does not accept legacy APKs.
./gradlew :app:bundleRelease
# -> app/build/outputs/bundle/release/app-release.aab  (signed, uploadable)
```

Verify what you produced:

```bash
$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Behaviour in each situation:

| `keystore.properties` | Result |
|---|---|
| Present and complete | `app-release.apk`, signed and installable |
| Absent | `app-release-unsigned.apk`; build still **succeeds** |
| Present but missing a key | Build **fails** with `keystore.properties is missing the required '<key>' entry.` |

The absent case is intentional: a fresh clone can still run `assembleRelease` without being
handed signing material first.

> **Never commit `keystore.properties` or the keystore.** Both are gitignored (`keystore.properties`,
> `*.jks`, `*.keystore`, `/keystore/`). Only the credential-free `keystore.properties.template`
> is tracked. Losing the keystore means you can no longer ship updates under the same key, so
> back it up somewhere durable.

For a real Play listing, prefer **Play App Signing**: you upload with this key and Google holds
the app signing key, so a lost upload key can be reset.


## Key files

| File | Role |
|---|---|
| `trials/GameTrialsManager.java` | Sole seam to the SDK; entitlement, `stopTrial` (Java) |
| `trials/PlayTrialsBinding.java` | Reflective resolution of the SDK surface (Java, package-private) |
| `trials/TrialAvailability.java` | SDK-free domain type; `Kind` enum + `detail` (Java) |
| `trials/TrialOutcome.java` | SDK-free domain type; `Kind` enum + `reason` (Java) |
| `MainViewModel.kt` | Level/XP state, quest & boss logic, milestone branch |
| `MainActivity.kt` | Hosts Compose; performs the `Activity`-scoped SDK call |
| `game/GameModels.kt` | `GameRules` (pure, unit-tested) + `GameUiState` |
| `ui/GameScreen.kt` | Compose UI, trial status banner, QA controls |

### Why the SDK layer is Java but the UI is Kotlin

Jetpack Compose **cannot** be written in Java — `@Composable` is implemented by a Kotlin
compiler plugin. So the split is drawn at the SDK seam: everything a partner needs to copy
when integrating Play Trials (`trials/`) is plain Java, while the UI stays Kotlin/Compose.

The two domain types use an inner `Kind` enum plus a detail string rather than a class
hierarchy, because Kotlin does not treat a Java class hierarchy as sealed — subclasses would
force an `else` branch on every `when`. Kotlin *does* honour exhaustive `when` over a Java
enum, so call sites stay exhaustive and compiler-checked.
