import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Version.
//
// Defaults live here so a local build needs no setup. CI overrides them from
// the git tag (`DSH_VERSION_CODE` / `DSH_VERSION_NAME`), which keeps the tag,
// the APK metadata and the update manifest from drifting apart 鈥?they have
// drifted before, and the symptom is an update prompt that never goes away.
//
// Keep these in step with the newest CHANGELOG entry: the scheme is
// major*10000 + minor*100 + patch, so 1.1.8 is 10108. See docs/RELEASING.md.
// ---------------------------------------------------------------------------
val appVersionCode: Int = (System.getenv("DSH_VERSION_CODE") ?: "10108").toIntOrNull()
    ?: error("DSH_VERSION_CODE must be an integer")
val appVersionName: String = System.getenv("DSH_VERSION_NAME") ?: "1.1.8"

// ---------------------------------------------------------------------------
// Release signing.
//
// Two sources, tried in order:
//   1. Environment variables 鈥?what CI uses, fed from repository secrets.
//   2. `keystore.properties` beside this file 鈥?what a local release uses.
//
// When neither is present the release build stays unsigned rather than failing:
// `assembleDebug` and CI compile checks must work on a fresh clone.
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/** The decoded keystore CI writes out before building. */
val ciKeystorePath: String? = System.getenv("DSH_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

val signingStoreFile: File? = when {
    ciKeystorePath != null -> File(ciKeystorePath).takeIf { it.exists() }
    else -> keystoreProps.getProperty("storeFile")
        ?.let { rootProject.file(it) }
        ?.takeIf { it.exists() }
}

val signingStorePassword: String? =
    System.getenv("DSH_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val signingKeyAlias: String? =
    System.getenv("DSH_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val signingKeyPassword: String? =
    System.getenv("DSH_KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")

val hasSigning = signingStoreFile != null &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "ai.deepseek.dshmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.deepseek.dshmobile"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("en", "zh")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Sign debug builds with the same key when one is available, so a
            // debug install can be upgraded in place by a release build.
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Surface the resolved version so a build log always states what it produced.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        logger.lifecycle("Building DSH Mobile $appVersionName ($appVersionCode), signed=$hasSigning")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Scanning the desktop's pairing QR. Two artifacts, not the ~12 that a
    // CameraX + zxing-core build needs, because this library ships its own
    // capture Activity. It needs neither appcompat nor a support library: its
    // `CaptureActivity` extends the framework `android.app.Activity`, and its
    // theme derives from a framework theme, so it does not care that this app
    // uses `android:Theme.Material.NoActionBar`. `ScanContract` is already an
    // `ActivityResultContract`, and the capture Activity requests the CAMERA
    // permission itself.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
