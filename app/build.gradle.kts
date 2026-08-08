// Release signing must come ONLY from environment variables that the CI workflow
// populates from GitHub Secrets (see .github/workflows/android-build.yml).
// Nothing secret is ever written to this file or anywhere else in source control.
val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")

val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    file(releaseStoreFile).exists()

plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
    // compiles src/main/kotlin automatically. See root build.gradle.kts.
}

android {
    namespace = "com.recorderx.app"

    // Android 17 (API 37) is current stable as of this build. AGP 9.3 supports
    // up to API 37, and Play Console requires targeting API 36+ for new
    // submissions starting August 2026, so 37 keeps headroom.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.recorderx.app"

        // Hard requirement from the spec: Android 8.0 (Oreo) and up.
        minSdk = 26
        targetSdk = 37

        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falls back to debug signing when RELEASE_* secrets aren't configured,
            // so `assembleRelease` always produces an installable smoke-test APK in
            // CI even before a real signing key is set up. Configure the secrets
            // (see README) to get a genuinely release-signed build.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // Deliberately off: the settings screen is built programmatically
        // (see MainActivity#buildRootView) and the one XML layout that does
        // exist (overlay_recording_controls.xml) is inflated directly with
        // LayoutInflater, so a generated Binding class would just be unused
        // generated code -- the opposite of "no unnecessary output."
        viewBinding = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.version",
            "kotlin/**",
            "DebugProbesKt.bin"
        )
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Deliberately tiny dependency set -- every artifact here is pulled in for
    // a concrete, load-bearing reason. No networking, image loading, DI,
    // reactive-stream, or layout libraries beyond what's actually used: the
    // whole settings screen is plain LinearLayouts built in Kotlin (see
    // MainActivity#buildRootView), so there's no ConstraintLayout dependency
    // to carry either.
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
