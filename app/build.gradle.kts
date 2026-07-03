plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "it.lagunav.openlagunamaps"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "it.lagunav.openlagunamaps"
        minSdk = 24
        targetSdk = 36
        versionCode = 62
        versionName = "1.29-splash-branding-testo"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Fragment
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // Splash Screen API
    implementation("androidx.core:core-splashscreen:1.2.0")

    // MapLibre Native
    implementation("org.maplibre.gl:android-sdk:11.11.0")
    
    // Serialization (per caricare il grafo JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    // Coroutines (per network calls Nominatim in background)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}