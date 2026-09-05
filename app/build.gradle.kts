import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputDirectory
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val rustRoot = rootProject.file("../rust")
val rustBuildScript = rootProject.file("scripts/build_rust_android.sh")
val toolsRoot = rootProject.file("../tools")
val defaultGameRoot = rootProject.file("../first-game")
val secondGameRoot = rootProject.file("../second-game")
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { stream -> load(stream) }
}
fun configuredValue(name: String): String? =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name)

val configuredGameBaseUrl = configuredValue("CUBACADABRA_GAME_BASE_URL")
val configuredBackendUrl = configuredValue("CUBACADABRA_BACKEND_URL")
val releaseGameBaseUrl = configuredGameBaseUrl ?: "https://cubacadabra.com/games/first-game/"
val releaseBackendUrl = configuredBackendUrl ?: "wss://api.cubacadabra.com"
val releaseUsesCleartext = releaseGameBaseUrl.startsWith("http://") || releaseBackendUrl.startsWith("ws://")

abstract class BuildRustTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

abstract class BuildGamePackageTask : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val buildGamePackage = tasks.register<BuildGamePackageTask>("buildGamePackage") {
    outputDirectory.set(layout.buildDirectory.dir("generated/game-assets"))
    inputs.files(
        fileTree(defaultGameRoot) { exclude("build/**") },
        fileTree(secondGameRoot) { exclude("build/**") },
        fileTree(toolsRoot) { exclude(".venv/**", "__pycache__/**") },
    )
    environment("PYTHONPATH", toolsRoot.resolve("src").absolutePath)
    commandLine(
        "python3", "-m", "cubacadabra", "build-game", defaultGameRoot.absolutePath,
        "--output", outputDirectory.get().asFile.resolve("game-package").absolutePath,
    )
    doLast {
        project.exec {
            environment("PYTHONPATH", toolsRoot.resolve("src").absolutePath)
            commandLine(
                "python3", "-m", "cubacadabra", "build-game", secondGameRoot.absolutePath,
                "--output", outputDirectory.get().asFile.resolve("game-package-second-game").absolutePath,
            )
        }
    }
}

val buildRust = tasks.register<BuildRustTask>("buildRust") {
    outputDirectory.set(layout.buildDirectory.dir("generated/rust/jniLibs"))
    inputs.files(fileTree(rustRoot) { exclude("target/**") })
    inputs.file(rustBuildScript)
    commandLine("sh", rustBuildScript.absolutePath, rustRoot.absolutePath, outputDirectory.get().asFile.absolutePath)
}

android {
    namespace = "dev.andrewarrow.cubacadabra"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cubacadabra.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

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
        resValues = true
    }

    signingConfigs {
        getByName("debug") {
            storePassword = "testing"
            keyAlias = "key0"
            keyPassword = "testing"
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "CUBACADABRA_GAME_BASE_URL", "\"${configuredGameBaseUrl ?: "http://127.0.0.1:5173/games/first-game/"}\"")
            buildConfigField("String", "CUBACADABRA_BACKEND_URL", "\"${configuredBackendUrl ?: "ws://127.0.0.1:8787"}\"")
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "CUBACADABRA_GAME_BASE_URL", "\"$releaseGameBaseUrl\"")
            buildConfigField("String", "CUBACADABRA_BACKEND_URL", "\"$releaseBackendUrl\"")
            resValue("bool", "allow_cleartext_traffic", releaseUsesCleartext.toString())
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
        variant.sources.assets?.addGeneratedSourceDirectory(
            buildGamePackage,
            BuildGamePackageTask::outputDirectory,
        )
    }
}

tasks.named("preBuild") {
    dependsOn(buildRust, buildGamePackage)
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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
}
