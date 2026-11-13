import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key. keystore.properties is git-ignored and points at the .jks, which lives
// outside the repo (see README). Without it, release builds fall back to the debug key and
// can't update an installed release build.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.renelabs.movebridge"
    compileSdk = 36
    ndkVersion = "28.2.13676358"   // r28+ aligns native libs to 16 KB pages (required by Play)

    defaultConfig {
        applicationId = "com.renelabs.movebridge"
        minSdk = 29          // AudioPlaybackCapture needs Android 10+
        targetSdk = 36
        versionCode = 19
        versionName = "0.19"
        // The project's public page (GitHub). If empty, the app hides its links to it.
        buildConfigField("String", "REPO_URL", "\"https://github.com/rene415/move-bridge\"")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("keystore.properties not found: release build is signed with the DEBUG key")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true         // IShellCapture (shell helper <-> app)
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true  // NASA rule 10: zero warnings
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        // Reflection in shell/ only runs inside the shell-uid helper, where hidden-API rules don't apply.
        disable += setOf("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
        // Built for arm64 phones only (USB host + the Move); no ChromeOS x86 build.
        disable += "ChromeOsAbiSupport"
        // "A newer version is available" notices: dependency updates are done deliberately, not by lint.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")  // @RequiresApi

    // Built-in shell starter: talks to the phone's own Wireless debugging (pairing, TLS, shell).
    // libadb-android is dual GPL-3.0-or-later / Apache-2.0; we use it under Apache-2.0.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Self-signed certificate for our ADB key (same BouncyCastle line libadb already pulls in).
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
}
