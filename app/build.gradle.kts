plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.macrocam"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.macrocam"
        minSdk = 22
        targetSdk = 34
        versionCode = 6
        versionName = "2.4"

        // Solo le architetture dei telefoni reali: l'app resta più piccola (OpenCV porta le sue librerie native)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Firma fissa: ogni build ha la stessa firma, quindi gli aggiornamenti
    // si installano sopra la versione già presente (senza disinstallare).
    signingConfigs {
        getByName("debug") {
            storeFile = file("macrocamera.keystore")
            storePassword = "macrocamera"
            keyAlias = "macrocamera"
            keyPassword = "macrocamera"
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
    // OpenCV per Android (da Maven Central): serve all'elaborazione del bokeh
    implementation("org.opencv:opencv:4.9.0")
}
