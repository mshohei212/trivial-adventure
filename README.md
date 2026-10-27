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
                            │  level >= TRIAL_MILESTONE_LEVEL
                            ▼
              GameEffect.RequestStopTrial  (one-shot effect)
                            │
                            ▼
              MainActivity  →  GameTrialsManager.stopTrial(activity)
                            │
                            ▼
                    TrialOutcome  →  viewModel.onTrialOutcome(...)
```

The comparison is `>=`, not `==`. If the paywall does not actually engage (sideloaded build or
enforcement disabled) the player advances past Level 5, and an equality check would silently
disarm the milestone forever. With `>=` **every** boss at or beyond the milestone re-asserts the
trial.

`stopTrial` needs a foreground `Activity` (the paywall attaches to its window), and a ViewModel
must never hold one — hence the effect hop through the Activity. The SDK's own signature asks for
a `Context`, and passing the foreground `Activity` keeps the paywall attached to the visible window.

### Outcomes

| Outcome | When | Game behaviour |
|---|---|---|
| `TrialStopped` | Trial active and enforceable | **No reward.** Play raises the paywall; the player stays on the boss level so returning resumes exactly there. |
| `NotEnforced` | `stopTrial()` returned, but Play's licensing client is absent (not installed by Play) | Toast *"Full Game Unlocked! Boss Defeated!"*, advance a level — **fail open** |
| `AlreadyUnlocked` | User owns the full game (`FULL_CHECK_OK` or QA override) | Same as above |
| `Failed(reason)` | SDK threw or called off the main thread | Same as above (**fail open**) |

> [!IMPORTANT]
> Every outcome except `TrialStopped` fails open. A broken or non-Play build must never
> soft-lock a tester behind a boss they cannot fight.


### The boss gate

`GameRules.applyXp` stops levelling the moment a boss level is reached, banking any surplus XP.
Without this, a lucky 40-XP roll could carry the player from Level 4 straight past Level 5 and
the paywall would never fire.

---

## The SDK

`app/build.gradle.kts` directly depends on [`app/libs/play-trials-sdk.aar`](app/libs/play-trials-sdk.aar):

```kotlin
implementation(files("libs/play-trials-sdk.aar"))
```

### What the AAR contains (v1.0.0)

The entire public surface is a single static method:

```java
public final class com.google.android.play.trials.PlayTrials {
    public static void stopTrial(android.content.Context);
}
```

Key runtime behaviors on Play-distributed vs. local builds:

- **No public entitlement query API in the AAR.** `PlayTrials` provides no `isFullVersion()` or
  `getTrialState()` method. However, when Google Play distributes the APK with Automatic Integrity
  Protection (Installer check) enabled, Play injects `com.pairip.licensecheck.TrialClient` and
  `com.pairip.licensecheck.LicenseClient` into **both** trial APKs and full-version APKs (purchased
  or Internal Testing track). `GameTrialsManager` inspects `LicenseClient.licenseCheckState`:
  - `FULL_CHECK_OK` → the user owns the full version (`AlreadyUnlocked`).
  - `REPEATED_CHECK_REQUIRED` → the user is in an active trial (`TrialStopped`).
- **`stopTrial()` silently no-ops on sideloaded builds.** Internally it reflects into
  `com.pairip.licensecheck.TrialClient` and only logs a warning when absent. On a sideloaded
  (`adb install`) build, `GameTrialsManager` detects the absence of `TrialClient` and returns
  `NotEnforced` so the player is not soft-locked at Level 5.

```bash
adb logcat -s GameTrialsManager PlayTrials LicenseClient
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
| Published as an Android App Bundle (`.aab`); legacy APKs unsupported | ✅ `./gradlew :app:bundleRelease` (includes native debug symbols) |
| `minSdkVersion` ≥ 24 (for Play installer checks) | ✅ `minSdk = 24` |
| `play-trials-sdk.aar` in `libs/` **and** declared in `build.gradle.kts` | ✅ `implementation(files("libs/play-trials-sdk.aar"))` |
| `<meta-data android:name="com.google.android.play.tbyb.trial_type" android:value="progression" />` | ✅ verified in the release merged manifest |
| `stopTrial()` called on the main thread, not an isolated process | ✅ enforced at runtime in `GameTrialsManager.stopTrial` |

### Play Console / security

| Requirement | Status |
|---|---|
| Play App Signing enabled | ✅ |
| **Installer checks (Automatic Protection) enabled** — strict prerequisite | ✅ |
| AAB uploaded to an Open or Internal testing track | ✅ |
| Testers opted in via the opt-in URL | ✅ |


> [!IMPORTANT]
> Trial enforcement depends on **installer checks** being enabled in the Play Console and on the
> build being installed **by Play** as a trial. A sideloaded APK will not enforce the trial, and
> an Internal Testing track install grants a full-version license (`FULL_CHECK_OK`).

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

Toolchain: **AGP 8.13.2 / Gradle 8.14.5 / Kotlin 2.2.21**, `compileSdk` and `targetSdk` 36,
`minSdk` 24, Java 17 bytecode.

Requires JDK 17–21. AGP rejects newer JDKs, so if your default is newer, pin it per invocation:

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
| `trials/GameTrialsManager.java` | Direct `PlayTrials.stopTrial(activity)` integration & entitlement check (Java) |
| `trials/TrialAvailability.java` | Domain type (`TRIAL_ACTIVE`, `FULL_VERSION`) (Java) |
| `trials/TrialOutcome.java` | Domain type (`TRIAL_STOPPED`, `ALREADY_UNLOCKED`, `NOT_ENFORCED`, `FAILED`) (Java) |
| `MainViewModel.kt` | Level/XP state, quest & boss logic, milestone branch |
| `MainActivity.kt` | Hosts Compose; performs the `Activity`-scoped SDK call |
| `game/GameModels.kt` | `GameRules` (pure, unit-tested) + `GameUiState` |
| `ui/GameScreen.kt` | Compose UI, trial status banner, QA controls |

