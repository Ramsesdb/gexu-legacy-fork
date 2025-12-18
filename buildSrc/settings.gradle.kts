dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
        create("androidx") {
            from(files("../gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("../gradle/compose.versions.toml"))
        }
        create("kotlinx") {
            from(files("../gradle/kotlinx.versions.toml"))
        }
    }
    // Preferir repos de settings
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Official sources first, JetBrains cache-redirector as fallback
        google()
        mavenCentral()
        maven(url = "https://repo1.maven.org/maven2")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
    }
}

pluginManagement {
    repositories {
        // Official sources first, JetBrains cache-redirector as fallback
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://repo1.maven.org/maven2")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
    }
}

rootProject.name = "mihon-buildSrc"
