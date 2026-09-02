import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputDirectory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val rustRoot = rootProject.file("../rust")
val rustBuildScript = rootProject.file("scripts/build_rust_android.sh")

abstract class BuildRustTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val buildRust = tasks.register<BuildRustTask>("buildRust") {
    outputDirectory.set(layout.buildDirectory.dir("generated/rust/jniLibs"))
    inputs.files(fileTree(rustRoot) { exclude("target/**") })
    commandLine("sh", rustBuildScript.absolutePath, rustRoot.absolutePath, outputDirectory.get().asFile.absolutePath)
}

android {
    namespace = "dev.andrewarrow.cubacadabra"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.andrewarrow.cubacadabra"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "CUBACADABRA_GAME_BASE_URL", "\"${providers.gradleProperty("CUBACADABRA_GAME_BASE_URL").orNull ?: "http://127.0.0.1:5173/games/first-game/"}\"")
        buildConfigField("String", "CUBACADABRA_BACKEND_URL", "\"${providers.gradleProperty("CUBACADABRA_BACKEND_URL").orNull ?: "ws://127.0.0.1:8787"}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildRust,
            BuildRustTask::outputDirectory,
        )
    }
}

tasks.named("preBuild") {
    dependsOn(buildRust)
}

tasks.configureEach {
    if (name.contains("externalNativeBuild", ignoreCase = true)) dependsOn(buildRust)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
}
