buildscript {
    repositories {
        google()
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
    dependencies {
        classpath(libs.android.shortcut.gradle)
    }
}

plugins {
    alias(kotlinx.plugins.serialization) apply false
    alias(libs.plugins.aboutLibraries) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.moko) apply false
    alias(libs.plugins.sqldelight) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
