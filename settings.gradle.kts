pluginManagement {
    resolutionStrategy {
        eachPlugin {
            val regex = "com.android.(library|application)".toRegex()
            if (regex matches requested.id.id) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        // Orden recomendado con mirrors estables
        gradlePluginPortal()
        google()
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("kotlinx") {
            from(files("gradle/kotlinx.versions.toml"))
        }
        create("androidx") {
            from(files("gradle/androidx.versions.toml"))
        }
        create("compose") {
            from(files("gradle/compose.versions.toml"))
        }
    }
    // Preferir repos definidos aquí sobre los de proyecto
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Orden recomendado con mirrors estables
        google()
        maven(url = "https://cache-redirector.jetbrains.com/maven-central")
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2")
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
        maven(url = "https://jitpack.io") // el proyecto usa artefactos de JitPack
        maven(url = "https://maven.ghostscript.com")
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "Gexu"
include(":app")
include(":core-metadata")
include(":core:archive")
include(":core:common")
include(":data")
include(":domain")
include(":i18n")
include(":macrobenchmark")
include(":presentation-core")
include(":presentation-widget")
include(":source-api")
include(":source-local")
include(":telemetry")
