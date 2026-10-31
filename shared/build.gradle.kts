import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// Code that does not need the Android app shell: lookup, cache, OCR text logic, the analysis
// pipeline, settings and the overlay UI. The platform-free core lives in commonMain and also
// builds for iOS (iosArm64, iosSimulatorArm64); the overlay UI and theme stay in androidMain until
// the Compose Multiplatform move.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Common tests read the HTML fixtures from disk with kotlinx-io. The absolute path of the fixture
// folder is generated as a constant, which works on the JVM and in the iOS simulator (it shares the
// host file system).
val fixturesPath: String = layout.projectDirectory.dir("src/commonTest/resources").asFile.invariantSeparatorsPath
val generateFixturePath = tasks.register("generateFixturePath") {
    val out = layout.buildDirectory.dir("generated/fixturePath/kotlin")
    val path = fixturesPath
    inputs.property("path", path)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/macle/royaleassist/FixturePath.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package com.macle.royaleassist\n\n" +
                "/** Generated: absolute path of src/commonTest/resources. */\n" +
                "internal const val FIXTURES_DIR = \"$path\"\n",
        )
    }
}

kotlin {
    android {
        namespace = "com.macle.royaleassist.shared"
        compileSdk = 37
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {
            isReturnDefaultValues = true
        }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }

    sourceSets {
        // Foundation/cinterop APIs (NSString, NSSearchPathForDirectoriesInDomains, ...) on every iOS source set.
        matching { it.name.startsWith("ios") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ksoup)
            api(libs.room3.runtime)
            api(libs.androidx.datastore.preferences.core)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.graphics)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.coil.compose)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqlite.bundled)
        }
        commonTest {
            kotlin.srcDir(generateFixturePath)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.io.core)
            }
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
    }
}

composeCompiler {
    // Compose is only used by the Android overlay UI for now; keep the plugin off the iOS compilations.
    targetKotlinPlatforms.set(setOf(KotlinPlatformType.androidJvm))
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
}
