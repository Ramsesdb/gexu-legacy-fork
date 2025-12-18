plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "tachiyomi.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    sqldelight {
        databases {
            create("Database") {
                packageName.set("tachiyomi.data")
                dialect(libs.sqldelight.dialects.sql)
                schemaOutputDirectory.set(project.file("./src/main/sqldelight"))
                // Desactivar verificación de migraciones para evitar fallo de SQLite JDBC nativo en entornos donde no está disponible
                verifyMigrations.set(false)
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

// Deshabilita todas las tareas VerifyMigration de este módulo
// (usamos la clase con nombre totalmente calificado para evitar imports en medio del script)
tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.domain)
    implementation(projects.core.common)

    api(libs.bundles.sqldelight)

    // MediaPipe for Local Embeddings (on-device AI)
    // MediaPipe tasks-text includes TFLite internally, no need for separate tensorflow deps
    implementation(libs.mediapipe.tasks.text)
}
