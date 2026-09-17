import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// -------------------------------------------------------------------------------------------
// Release signing credentials.
//
// An unsigned APK cannot be installed on a device or uploaded to Play, so release builds are
// signed with a keystore described by `keystore.properties` at the repo root. Both that file
// and the keystore it points at are gitignored: signing material must never be committed.
//
// This is deliberately OPTIONAL. A fresh clone has no keystore.properties, and in that case
// `assembleRelease` still succeeds and simply emits an unsigned APK, rather than failing with
// a confusing "storeFile not set" error from AGP. See "Release signing" in the README.
// -------------------------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties: Properties? = if (keystorePropertiesFile.exists()) {
    Properties().apply { keystorePropertiesFile.inputStream().use { load(it) } }
} else {
    null
}

/** Reads [key] from keystore.properties, failing loudly rather than producing a broken APK. */
fun Properties.requireProperty(key: String): String =
    getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("keystore.properties is missing the required '$key' entry.")

android {
    namespace = "com.GoogleLLC.TrivialAdventure"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.GoogleLLC.TrivialAdventure"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Milestone that terminates the Play Trial. Surfaced through BuildConfig so QA can
        // re-point the paywall trigger without touching game logic.
        buildConfigField("int", "TRIAL_MILESTONE_LEVEL", "5")
    }

    signingConfigs {
        // Only registered when credentials are present; `findByName` below then yields null
        // for a fresh clone and the release APK stays unsigned instead of failing the build.
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.requireProperty("storeFile"))
                storePassword = props.requireProperty("storePassword")
                keyAlias = props.requireProperty("keyAlias")
                keyPassword = props.requireProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Keeps sandbox builds installable side-by-side with a store build of the same app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-sandbox"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null when keystore.properties is absent -> unsigned APK, build still succeeds.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    // ---------------------------------------------------------------------------------
    // Google Play Trials SDK (Try Before You Buy / Progression API).
    //
    // The SDK is distributed to partners as `play-trials-sdk.aar` and is NOT published to
    // google()/mavenCentral(). Drop the AAR into `app/libs/` and it is picked up here
    // automatically -- no further Gradle edits required.
    //
    // If/when the artifact is published to the Google Maven repo, replace the fileTree
    // dependency below with the coordinate instead:
    //
    //     implementation("com.google.android.play:trials:1.0.0")
    //
    // GameTrialsManager binds to the SDK reflectively, so the module compiles and runs
    // whether or not the AAR is present (see TrialAvailability.Kind.SDK_MISSING).
    // ---------------------------------------------------------------------------------
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
