// Top-level build file. Plugins are declared here (without applying them) so that
// the versions resolved in gradle/libs.versions.toml are shared by all modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
