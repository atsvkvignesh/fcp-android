plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// GitHub Actions run number becomes the version, so every build installs as an update.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.fibrocoir.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fibrocoir.app"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fixed signing key: keep app/release.keystore safe. Every future APK must be signed
    // with this same key, or phones will refuse to install it as an update.
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("FCP_STORE_PASSWORD") ?: "FibroCoir@1990"
            keyAlias = "fibrocoir"
            keyPassword = System.getenv("FCP_KEY_PASSWORD") ?: "FibroCoir@1990"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
