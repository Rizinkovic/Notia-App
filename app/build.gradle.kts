plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: use the repository secrets when present (see README), otherwise the bundled
// debug key. The bundled key is the one the previous Expo build used, so the new APK installs
// over it and keeps the user's data.
val keystoreFile = System.getenv("NOTIA_KEYSTORE_FILE")?.let { file(it) } ?: file("notia-debug.keystore")

android {
    namespace = "com.notia.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notia.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "2.0.0"
        resourceConfigurations += listOf("en", "fr")
    }

    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = System.getenv("NOTIA_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("NOTIA_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("NOTIA_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf(
            "/META-INF/*.version", "/META-INF/*.kotlin_module", "/META-INF/{AL2.0,LGPL2.1}",
            "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "/kotlin/**", "**/*.kotlin_builtins",
        )
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    // Only dependency: QR scanning through Google Play services (no camera permission, no bundled model).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
