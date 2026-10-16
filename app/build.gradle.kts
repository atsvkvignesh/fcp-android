plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

// GitHub Actions run number becomes the version, so every build installs as an update.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Addresses that aren't secret (sheet ids, the upload script) live in fcp.properties at the
// top of the repo. An environment variable with the FCP_ name wins when set.
val fcpProps = java.util.Properties().apply {
    rootProject.file("fcp.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun fcp(env: String, key: String): String = System.getenv(env)?.takeIf { it.isNotBlank() } ?: fcpProps.getProperty(key, "").trim()

android {
    namespace = "com.fibrocoir.app"
    compileSdk = 35

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.fibrocoir.app"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "2.0.$buildNumber"
        // the small Apps Script that stores photos and bills in Drive (apps-script/uploads)
        buildConfigField("String", "UPLOAD_URL", "\"${fcp("FCP_UPLOAD_URL", "uploadUrl")}\"")
        // sheet ids that aren't fixed in the code (they can also be pasted in the app)
        buildConfigField("String", "HUB_SHEET_ID", "\"${fcp("FCP_HUB_SHEET_ID", "hubSheetId")}\"")
        buildConfigField("String", "INVENTORY_SHEET_ID", "\"${fcp("FCP_INVENTORY_SHEET_ID", "inventorySheetId")}\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // the screenshot test reads the same golden data as the unit tests
    sourceSets["androidTest"].assets.srcDirs("src/test/resources")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")  // Firebase brings an old one; the permission prompt needs 1.3+
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
