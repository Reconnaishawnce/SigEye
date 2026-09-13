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
        //
        // The leading 0.1 replaces a placeholder 1.0 that never meant anything. It reads
        // as going backwards and is not: Android orders updates by versionCode, which is
        // the run number and still only ever climbs. What the name now says is true, which
        // is that this is the first version anybody other than its author has been asked
        // to use.
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0") +
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
            // Signed with the same stable key so a locally built debug APK still installs
            // over a release one, which is the difference between testing a fix on the
            // phone and uninstalling to test a fix on the phone.
            if (signingStore != null) signingConfig = signingConfigs.getByName("sigeye")
        }
        release {
            /*
             * What ships. It used to be the debug build, which meant the published APK was
             * debuggable: any process with adb access could attach to it and read memory.
             * For a tool whose whole job is watching radio traffic and keeping lists of
             * devices, that is not a detail.
             *
             * Minification stays off, and that is a decision rather than an oversight.
             * This is an instrument for a paper, so somebody must be able to read what it
             * does; and CrashLog hands the user a stack trace to send, which R8 would turn
             * into a page of single letters unless the mapping file went out with every
             * build. There is no store listing and no size pressure buying anything back.
             */
            isMinifyEnabled = false
            isShrinkResources = false
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
    // Android's org.json is an empty stub in unit tests: every method throws "not mocked".
    // Anything here that serialises itself - a saved follow, a snapshot, a store - would
    // then be untestable on the JVM, which is exactly the code most worth testing. This
    // puts a real implementation on the test classpath only; the app still uses Android's.
    testImplementation(libs.json)
}
