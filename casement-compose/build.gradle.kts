import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.binary.compatibility.validator)
}

val openJfxModules = listOf("base", "graphics", "controls", "media", "web", "swing")

/** The compile classpath needs the platform jars of the machine that builds; consumers bring their own. */
val compileClassifier: String =
    System.getProperty("os.name").lowercase().let { os ->
        val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
        when {
            "mac" in os -> if (arm) "mac-aarch64" else "mac"
            "win" in os -> "win"
            else -> if (arm) "linux-aarch64" else "linux"
        }
    }

// OpenJFX ships its classes in per-OS jars, picked here by the machine that runs the code. A dedicated
// non-transitive configuration keeps Gradle from falling back to the empty platform-less jars.
val javafxPlatform: Configuration =
    configurations.create("javafxPlatform") {
        isTransitive = false
        isCanBeConsumed = false
        isCanBeResolved = true
    }
run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    val classifier =
        when {
            "mac" in os -> if (arm) "mac-aarch64" else "mac"
            "win" in os -> "win"
            else -> if (arm) "linux-aarch64" else "linux"
        }
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach {
        dependencies.add("javafxPlatform", "org.openjfx:javafx-$it:${libs.versions.openjfx.get()}:$classifier")
    }
}

kotlin {
    explicitApi()

    android {
        namespace = "com.jantursky.casement.compose"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest { isIncludeAndroidResources = true }
        withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":casement-core"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The desktop renderer needs OpenJFX at run time. It is not forced on consumers: they add javafx-web and
        // javafx-swing for their own OS (the org.openjfx.javafxplugin does this). Here it is compile-only.
        jvmMain.dependencies {
            openJfxModules.forEach {
                compileOnly(
                    "org.openjfx:javafx-$it:${libs.versions.openjfx.get()}:$compileClassifier",
                )
            }
        }
        jvmTest.dependencies {
            implementation(files(javafxPlatform))
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        getByName("androidDeviceTest").resources.srcDir(rootProject.file("fixtures/ext-apps"))
        getByName("androidDeviceTest").resources.srcDir(rootProject.file("fixtures/hostile"))
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.compose.ui.test.manifest)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib { enabled = true }
}

kover {
    reports {
        filters {
            excludes {
                // Platform renderers are exercised on devices and simulators, not in host tests.
                packages("com.jantursky.casement.compose.platform")
                // The composable and its session are exercised by the device and simulator tests.
                classes(
                    "com.jantursky.casement.compose.McpAppView*",
                    "com.jantursky.casement.compose.Load*",
                    "com.jantursky.casement.compose.Session",
                    "com.jantursky.casement.compose.WebViewSpec",
                    "com.jantursky.casement.compose.RendererFailure",
                )
            }
        }
        verify {
            rule {
                bound { minValue = 90 }
                bound {
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                    minValue = 78
                }
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "casement-compose")
    pom {
        name.set("Casement Compose")
        description.set(
            "McpAppView: render MCP Apps in a sandboxed WebView inside Compose Multiplatform, " +
                "with the host side of the MCP Apps bridge.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/jantursky/casement")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("jantursky")
                name.set("Jan Tursky")
                email.set("jan.tursky@gmail.com")
                url.set("https://jantursky.com")
            }
        }
        scm {
            url.set("https://github.com/jantursky/casement")
            connection.set("scm:git:https://github.com/jantursky/casement.git")
            developerConnection.set("scm:git:ssh://git@github.com/jantursky/casement.git")
        }
    }
}

// The library ships no Compose resources, and the plugin's copy task for device tests has no output directory
// with the Android KMP library plugin.
tasks.matching { it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets" }.configureEach { enabled = false }

// The wasm test run covers pure functions only (no UI), so it doesn't need the Skiko runtime that this check
// asks an executable binary for.
tasks.named { it == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
