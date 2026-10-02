// Compile/test harness used in CI-like environments without the Android SDK:
// - :core compiles and tests android/core against the JVM
// - :app compiles android/app sources against Robolectric's Android 9 framework jar (API 28)
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "walkdac-check"
include("core", "app")
