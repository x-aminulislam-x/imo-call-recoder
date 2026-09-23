plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.imorec"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.imorec"
        minSdk = 26
        // Deliberately 33, not 34. Targeting 34 pulls in Android 14's
        // foreground-service-type restrictions, which block a mic-type service
        // from starting in several situations this app relies on. It is
        // sideloaded, not shipped on Play, so there is no policy reason to
        // chase the newest target.
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
}
