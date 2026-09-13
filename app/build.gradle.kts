plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * A stable signing key is what makes in-place updates possible. Without it every CI run
 * generates a throwaway debug keystore, Android sees a different signer each build, and
 * refuses to install over the previous version - so an update means uninstalling and
 * losing the CSV logs.
 *
 * Supplied by CI from repository secrets. Absent locally, and absent on forks, in which
 * case the build falls back to the usual debug key and still works.
 */
val signingStore = System.getenv("SIGNING_KEYSTORE_PATH")
    ?.let { File(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.sigeye"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sigeye"
        minSdk = 26
        targetSdk = 34
        // Derived from the CI run so every published build is strictly newer than the
        // last, which is what Android and Obtainium compare when offering an update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        // Monotonic on purpose. A git SHA has no ordering, so anything comparing APK
        // versions rather than release tags cannot tell which build is newer.
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0") +
            (System.getenv("GITHUB_SHA")?.take(7)?.let { " ($it)" } ?: " (dev)")
    }

    signingConfigs {
        if (signingStore != null) {
            create("sigeye") {
                storeFile = signingStore
                storeType = "PKCS12"
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "sigeye"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                    ?: System.getenv("SIGNING_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // The shipped artifact is a debug build, so this is the one that must carry
            // the stable signature.
            if (signingStore != null) signingConfig = signingConfigs.getByName("sigeye")
        }
        release {
            isMinifyEnabled = false
            if (signingStore != null) signingConfig = signingConfigs.getByName("sigeye")
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
        // Wanted for the version name, which goes in a bug report - a report that does not
        // say which build it came from is a report nobody can act on.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
}
