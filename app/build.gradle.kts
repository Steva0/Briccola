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
        versionCode = 112
        versionName = "1.79-debug-pacchetto-offline"

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

    // Il database della mappa offline precotta (mbgl-offline.db, ~150MB) va bundlato SENZA
    // compressione: Android/AAPT ha bug noti nel leggere via AssetManager asset compressi molto
    // grandi (oltre gli ~100MB), che possono fallire in modo silenzioso invece di dare un errore
    // chiaro — probabile causa per cui il pacchetto offline non risultava mai davvero copiato/
    // utilizzabile al primo avvio.
    androidResources {
        noCompress += "db"
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