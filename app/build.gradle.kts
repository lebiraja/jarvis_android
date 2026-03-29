plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Lifecycle-aware service
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // ONNX Runtime for openWakeWord
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Offline Speech-to-Text
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Fuzzy contact matching
    implementation("me.xdrop:fuzzywuzzy:1.4.0")

    // Preferences DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
