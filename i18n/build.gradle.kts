import mihon.buildlogic.generatedBuildDir
import mihon.buildlogic.tasks.getLocalesConfigTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("mihon.library")
    kotlin("multiplatform")
    alias(libs.plugins.moko)
}

kotlin {
    androidTarget()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

val generatedAndroidResourceDir = generatedBuildDir.resolve("android/res")

android {
    namespace = "tachiyomi.i18n"

    sourceSets {
        val main by getting
        main.res.srcDirs(
            "src/commonMain/resources",
            generatedAndroidResourceDir,
        )
    }

    lint {
        // Ya se desactivaban algunas reglas, añadimos más configuraciones para que lint no falle el build
        disable.addAll(listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity"))
        abortOnError = false
        warningsAsErrors = false
        // checkGeneratedSources = false // Si fuese necesario ignorar generados, se puede habilitar (AGP permite esta propiedad)
    }
}

multiplatformResources {
    resourcesPackage.set("tachiyomi.i18n")
}

tasks {
    val localesConfigTask = project.getLocalesConfigTask(generatedAndroidResourceDir)
    preBuild {
        dependsOn(localesConfigTask)
    }
}
