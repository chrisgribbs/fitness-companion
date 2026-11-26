plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // The package name is also entered in your Google Cloud OAuth client. If you change it here,
    // change it there too.
    namespace = "com.fitcompanion.poc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fitcompanion.poc"
        minSdk = 28 // Health Connect supports Android 9+
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-poc"
        // Only 64-bit ARM, which nearly every phone from the last several years uses.
        // Cuts the APK size a lot (the map engine's native code is included once, not four times).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // A fixed debug key committed to this private repo, so every GitHub build installs over the
        // previous one and the SHA-1 registered with Google stays the same. A debug key isn't
        // a secret worth protecting; never use it for a Play Store release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.health.connect)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.maplibre.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.compose.icons.extended)
}
