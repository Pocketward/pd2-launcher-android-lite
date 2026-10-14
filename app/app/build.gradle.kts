plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// PD2 Launcher Lite: imports a Diablo II + PD2 folder and runs the official PD2 launcher (which
// starts Game.exe) with a reduced ARM64 Wine whose x86 code Box64's wowbox64.dll emulates. The
// main app's Jetpack Compose screens; one ABI.

/**
 * The versionCode of a versionName MAJOR.MINOR.PATCH[-betaN]: ((MAJOR * 100 + MINOR) * 100 +
 * PATCH) * 100 + N, with 99 for the release itself, so every later version has a higher code:
 * 0.1.0-beta1 is 10001, 0.1.0-beta2 10002, 0.1.0 10099, 0.1.1-beta1 10101.
 */
fun versionCodeOf(name: String): Int {
    val match = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})(?:-beta([1-9]|[1-8]\d|9[0-8]))?""").matchEntire(name)
        ?: error("versionName $name is not MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-betaN")
    val (major, minor, patch, beta) = match.destructured.toList().map { it.toIntOrNull() ?: 99 }
    return ((major * 100 + minor) * 100 + patch) * 100 + beta
}

// Release signing (docs/BUILDING.md): the key stays outside the repository. `build.sh release`
// mounts the keystore read-only and names it in PD2_LITE_KEYSTORE, with its passwords and alias
// in PD2_LITE_KEYSTORE_PASSWORD, PD2_LITE_KEY_ALIAS and PD2_LITE_KEY_PASSWORD.
val releaseKeystore: File? = System.getenv("PD2_LITE_KEYSTORE")?.let(::File)?.takeIf { it.isFile }

android {
    namespace = "com.pd2launcher.box64"
    compileSdk = 36
    buildToolsVersion = "36.0.0" // as installed in the build image
    ndkVersion = "27.2.12479018" // r27c, installed in the build image

    defaultConfig {
        applicationId = "com.pd2launcher.lite"
        // As the main app: Android 13 is the oldest seccomp policy our glibc answers, and
        // targetSdk 28 lets the app run programs from its own data folder.
        minSdk = 33
        targetSdk = 28
        versionName = "0.1.0"
        versionCode = versionCodeOf(versionName!!)
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("PD2_LITE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PD2_LITE_KEY_ALIAS")
                keyPassword = System.getenv("PD2_LITE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
            // Xvfb's abstract socket is device wide: the release and the dev build each have their own display.
            buildConfigField("String", "X_DISPLAY", "\":7\"")
        }
        getByName("debug") {
            // The development build installs beside the release app (its own name, icon and Files root).
            applicationIdSuffix = ".dev"
            buildConfigField("String", "X_DISPLAY", "\":8\"")
            // Measured as shipped: R8 shrinks the code even in this test build.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1" // as installed in the build image
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    androidResources {
        // The runtime archive is already zstd-compressed; stored, it is streamed from the APK.
        noCompress += "zst"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        disable += "OldTargetApi"
    }
}

// A release build is never left unsigned (the plugin would make app-release-unsigned.apk).
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (releaseKeystore == null) {
            throw GradleException(
                "release signing is missing: PD2_LITE_KEYSTORE must name the release keystore " +
                    "(build.sh release; docs/BUILDING.md)",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The main app's Compose versions.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Streaming zstd decompression of the runtime asset (as the main app).
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
    testImplementation("junit:junit:4.13.2")
    // org.json for the JVM tests (android.jar has only stubs of it).
    testImplementation("org.json:json:20240303")
}
