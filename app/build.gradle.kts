plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lucas.musicdownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lucas.musicdownloader"
        minSdk = 29 // Android 10 et plus
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
        ndk {
            // Les téléphones récents : on évite d'embarquer x86 pour alléger l'APK
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // Clé fixe commitée dans le dépôt : chaque nouvel APK peut s'installer
        // par-dessus le précédent sans désinstaller l'app.
        create("perso") {
            storeFile = file("perso.keystore")
            storePassword = "musicdownloader"
            keyAlias = "perso"
            keyPassword = "musicdownloader"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Obligatoire pour youtubedl-android (python et ffmpeg sont des binaires natifs)
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
