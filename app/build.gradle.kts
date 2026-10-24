plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.chenwuyi.leaf"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chenwuyi.leaf"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The commit the APK was built from, shown in 设置 › 关于 so a bug report can name the exact build.
        buildConfigField("String", "GIT_HASH", "\"${gitHash()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Self-use for now: release builds carry the debug key so CI can hand out an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.material3.expressive)
    implementation(libs.material.icons.extended)
    implementation(libs.graphics.shapes)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}

fun gitHash(): String =
    runCatching {
        providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
    }.getOrDefault("").ifEmpty { "unknown" }
