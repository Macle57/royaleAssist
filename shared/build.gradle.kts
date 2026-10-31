import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code that does not need the Android app shell: lookup, cache, OCR text logic, the analysis
// pipeline, settings and the Compose Multiplatform UI (overlay card, setup, history, verify chrome).
// Everything platform-free lives in commonMain and also builds for iOS (iosArm64,
// iosSimulatorArm64), packaged as the static framework "Shared".
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Common tests read the HTML fixtures from disk with kotlinx-io. The absolute path of the fixture
// folder is generated as a constant, which works on the JVM and in the iOS simulator (it shares the
// host file system).
val fixturesPath: String = layout.projectDirectory.dir("src/commonTest/resources").asFile.invariantSeparatorsPath
// The repo's sample screenshots (the iOS Vision OCR test reads them in the simulator).
val samplesPath: String = rootProject.layout.projectDirectory.dir("samples").asFile.invariantSeparatorsPath
val generateFixturePath = tasks.register("generateFixturePath") {
    val out = layout.buildDirectory.dir("generated/fixturePath/kotlin")
    val path = fixturesPath
    val samples = samplesPath
    inputs.property("path", path)
    inputs.property("samples", samples)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/macle/royaleassist/FixturePath.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package com.macle.royaleassist\n\n" +
                "/** Generated: absolute path of src/commonTest/resources. */\n" +
                "internal const val FIXTURES_DIR = \"$path\"\n\n" +
                "/** Generated: absolute path of the repo's samples folder. */\n" +
                "internal const val SAMPLES_DIR = \"$samples\"\n",
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
        // Compose resources (Res.drawable.*) are packaged as Android assets.
        androidResources {
            enable = true
        }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

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
            // The app module hosts these composables (ComposeView, setContent), so they are api.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            api(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
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

compose.resources {
    packageOfResClass = "com.macle.royaleassist.resources"
    publicResClass = true
    generateResClass = always
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
}

// `-PiosTestDevice=<UDID>` runs the simulator tests on that simulator, which must already be
// booted, instead of the default device that Kotlin boots and shuts down for the run.
providers.gradleProperty("iosTestDevice").orNull?.let { dev ->
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        device.set(dev)
        standalone.set(false)
    }
}
