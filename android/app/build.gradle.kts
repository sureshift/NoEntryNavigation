plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.noentrynav"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.noentrynav"
        minSdk = 21
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("VERSION_CODE") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // real phones only; x86 is for emulators
    }
    // One fixed signing key for every build, so a new APK installs over the old one.
    // CI can supply its own key through KS_FILE / KS_PASS (repository secrets); otherwise the key in android/signing is used.
    signingConfigs {
        create("routiq") {
            storeFile = file(System.getenv("KS_FILE") ?: "../signing/routiq.jks")
            storePassword = System.getenv("KS_PASS")?.takeIf { it.isNotEmpty() } ?: "Oxvh_h9A1Q8nZaLmIJJ-V9Ug"
            keyAlias = "routiq"
            keyPassword = System.getenv("KS_PASS")?.takeIf { it.isNotEmpty() } ?: "Oxvh_h9A1Q8nZaLmIJJ-V9Ug"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("routiq")
        }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
