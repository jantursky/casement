import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "com.jantursky.casement.demo"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }

    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "DemoKit"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":casement-compose"))
            api(project(":casement-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.resources {
    packageOfResClass = "com.jantursky.casement.demo.resources"
    generateResClass = always
    // The apps come from fixtures/ and samples/demo/apps, gathered in one folder at build time.
    customDirectory(
        sourceSetName = "commonMain",
        directoryProvider = layout.buildDirectory.dir("generated/demo-resources"),
    )
}

val gatherApps by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/demo-resources/files"))
    from(rootProject.file("fixtures/ext-apps")) { include("get-time.html") }
    from(rootProject.file("fixtures/hostile")) { include("hostile.html") }
    from(rootProject.file("samples/demo/apps")) { include("*.html") }
}

tasks
    .matching {
        it.name.contains("ComposeResources", ignoreCase = true) ||
            it.name.contains("Resource", ignoreCase = true)
    }.configureEach { dependsOn(gatherApps) }

compose.desktop {
    application {
        mainClass = "com.jantursky.casement.demo.MainKt"
    }
}
