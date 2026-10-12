plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Voice Lab: a scratch app for the LightComm voice-input facts (Android's on-device speech
// recognizer). Kept apart from Light Lab so it never disturbs the light tests.
android {
    namespace = "com.metavitae.voicelab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.metavitae.voicelab"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Test-only key (not a release key), same as Light Lab's, so CI builds update-install.
    signingConfigs {
        getByName("debug") {
            storeFile = file("voicelab-debug.jks")
            storePassword = "android"
            keyAlias = "lightlab"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
