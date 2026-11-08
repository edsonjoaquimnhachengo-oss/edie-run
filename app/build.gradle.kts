import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.edierun.vault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.edierun.vault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Chave fixa: todas as compilações saem com a mesma assinatura, por isso
    // as actualizações instalam-se por cima sem apagar o cofre.
    // Mantenha o repositório PRIVADO.
    signingConfigs {
        create("edie") {
            storeFile = file("edierun.jks")
            storePassword = "fb0d33af2a611e56c6c8a1bd"
            keyAlias = "edierun"
            keyPassword = "fb0d33af2a611e56c6c8a1bd"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("edie")
        }
        debug {
            signingConfig = signingConfigs.getByName("edie")
        }
    }

    // Um APK por arquitectura: arm64 (quase todos os telemóveis actuais) e arm32 (antigos).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // O yt-dlp (Python) precisa das bibliotecas nativas extraídas.
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Motor de downloads (yt-dlp + ffmpeg)
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")

    // Leitor (VLC)
    implementation("org.videolan.android:libvlc-all:3.7.6")

    // Tor
    implementation("info.guardianproject:tor-android:0.4.8.17.2")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
}
