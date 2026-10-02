import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets {
        main { kotlin.srcDir("../../../android/app/src/main/kotlin") }
    }
}

dependencies {
    implementation(project(":core"))
    // Real Android 9 (API 28) framework classes, so the app sources compile exactly as they would against android.jar.
    compileOnly("org.robolectric:android-all:9-robolectric-4913185")
}
