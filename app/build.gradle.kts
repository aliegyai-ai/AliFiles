plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// bump this for a big release; the build number from GitHub is added after it
val appVersion = "1.6"

android {
    namespace = "com.ali.files"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ali.files"
        minSdk = 29
        targetSdk = 35
        // CI passes -PbuildNumber so every build is newer than the last (needed for in-app updates)
        val buildNo = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
        versionCode = buildNo ?: 5
        versionName = if (buildNo != null) "$appVersion.$buildNo" else appVersion
        // both of Ali's devices (Galaxy A52s, MatePad 11.5 S) are 64-bit ARM; keeps the APK small
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // One fixed key for every build, so each new APK installs over the old one as an update
    // (the debug key was random on every GitHub build, which made Android refuse updates).
    signingConfigs {
        create("ali") {
            storeFile = file("alifiles.jks")
            storePassword = "alifiles123"
            keyAlias = "alifiles"
            keyPassword = "alifiles123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ali")
        }
        debug {
            signingConfig = signingConfigs.getByName("ali")
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
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true // yt-dlp's bundled Python must be extracted to disk
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")

    // built-in music & video player
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
