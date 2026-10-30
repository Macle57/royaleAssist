plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere (AGP 9 compiles Kotlin itself); declared so the build uses KGP 2.4.21
    // instead of the 2.2.10 that AGP depends on.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
