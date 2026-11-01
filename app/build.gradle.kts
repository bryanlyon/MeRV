plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.rvremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.rvremote"
        minSdk = 26
        targetSdk = 35
        // Each CI build gets a higher version so Android treats it as an upgrade.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "0.1.$run"
    }

    // Sign with the permanent key when CI provides it (from repo secrets), so new
    // APKs install over old ones. The key itself never lives in this repo.
    val ksPath = System.getenv("MERV_KEYSTORE")
    if (ksPath != null && file(ksPath).exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(ksPath)
                storePassword = System.getenv("MERV_KEYSTORE_PASSWORD")
                keyAlias = "merv"
                keyPassword = System.getenv("MERV_KEYSTORE_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
    }
}

// No libraries beyond the Kotlin standard library.
dependencies {}
