import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.walkdac.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.walkdac.receiver"
        minSdk = 28
        // The NW-A105 runs Android 9; targeting 28 keeps the behaviour we designed for (no FGS types, legacy storage rules).
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // sideload build; replace with your own key if you publish
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No resources are used: the UI is built in code and icons come from android.R.
    buildFeatures {
        buildConfig = false
        resValues = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
