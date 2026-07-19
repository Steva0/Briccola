import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Credenziali di firma release: keystore.properties e release.keystore restano fuori da git
// (vedi .gitignore) — chi clona il repo senza questi due file può comunque compilare la build
// debug normalmente, solo assembleRelease richiede di generarli (vedi RELEASE.md).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
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
        versionCode = 124
        versionName = "1.0.1-fix-origine-fuori-area"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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

    // I database della mappa offline precotta (tile vettoriali/raster, glifi — vedi
    // genera_tiles_offline.py) vanno bundlati SENZA compressione: devono restare SQLite validi
    // apribili direttamente dopo la copia in filesDir, e asset grandi compressi hanno bug noti
    // di lettura silenziosamente troncata via AssetManager.
    androidResources {
        noCompress += "db"
        noCompress += "mbtiles"
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

    // Server HTTP locale per servire la mappa offline precotta (tile/sprite/glifi) da
    // 127.0.0.1, sostituendo il meccanismo di cache offline nativo di MapLibre
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // Serialization (per caricare il grafo JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    // Coroutines (per network calls Nominatim in background)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}