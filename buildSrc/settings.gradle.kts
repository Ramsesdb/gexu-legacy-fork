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
        // Orden recomendado con mirrors estables
        google()
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
    }
}

pluginManagement {
    repositories {
        maven(url = "https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
        gradlePluginPortal()
        google()
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
    }
}

rootProject.name = "mihon-buildSrc"
