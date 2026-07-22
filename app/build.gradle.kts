plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.enderthor.trainerbridgeble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.enderthor.trainerbridgeble"
        minSdk = 26
        targetSdk = 34
        versionCode = 20260722
        versionName = "0.9.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the debug key so sideload/OTA installs on the Karoo aren't rejected as unsigned.
            // ponytail: fine for a community sideload; swap for a real release key if it ever ships to a store.
            signingConfig = signingConfigs.getByName("debug")
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
    // Raw ANT channel API — used ONLY for the corrected-power ANT+ output to a head unit (Garmin), which
    // reads a smart trainer over ANT+. The trainer itself is read over BLE (central); this is TX-only.
    implementation(files("libs/android_antlib_4-16-0.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.hammerhead:karoo-ext:1.1.9")

    testImplementation("junit:junit:4.13.2")
}
