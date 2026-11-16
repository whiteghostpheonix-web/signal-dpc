plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "ug.co.signalelectronics.devicefinance"
    compileSdk = 34

    defaultConfig {
        applicationId = "ug.co.signalelectronics.devicefinance"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "0.2.5"
    }

    signingConfigs {
        create("release") {
            val signingFile = providers.gradleProperty("SIGNAL_DPC_STORE_FILE").orNull
            val signingPassword = providers.gradleProperty("SIGNAL_DPC_STORE_PASSWORD").orNull
            storeFile = file(signingFile ?: error("SIGNAL_DPC_STORE_FILE is required for a release build"))
            storePassword = signingPassword ?: error("SIGNAL_DPC_STORE_PASSWORD is required")
            keyAlias = providers.gradleProperty("SIGNAL_DPC_KEY_ALIAS").orNull ?: "signal-dpc-release"
            keyPassword = providers.gradleProperty("SIGNAL_DPC_KEY_PASSWORD").orNull ?: signingPassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for backend calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager for periodic schedule enforcement
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
