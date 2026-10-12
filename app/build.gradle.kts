plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metavitae.lightlab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.metavitae.lightlab"
        minSdk = 29
        targetSdk = 34
        versionCode = 8
        versionName = "0.8"
    }

    // Test-only key (not a release key) kept in the repo so every CI build can
    // update-install over the previous one on the test phones.
    signingConfigs {
        getByName("debug") {
            storeFile = file("lightlab-debug.jks")
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
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { testLogging { showStandardStreams = true; events("passed", "failed") } }
