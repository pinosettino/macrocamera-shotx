plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.macrocam"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.macrocam"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
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
