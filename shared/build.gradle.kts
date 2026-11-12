import groovy.json.JsonSlurper

// Общий код Android + iOS (Kotlin Multiplatform + Compose Multiplatform).
// Rust-ядро: на Android — libchudo_core.so через JNA, на iOS — libchudo_core.a через cinterop (core/include/chudo_core.h).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/** Rust-цели iOS → папка со статической библиотекой (собирает scripts/build-ios-core.sh). */
val rustIos = mapOf(
    "iosArm64" to "aarch64-apple-ios",
    "iosSimulatorArm64" to "aarch64-apple-ios-sim",
)

kotlin {
    compilerOptions {
        // expect/actual-классы (Platform, NativeCore) — без предупреждения «in Beta».
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        target.compilations.getByName("main").cinterops.create("chudocore") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/chudocore.def"))
            includeDirs(rootProject.file("core/include"))
            extraOpts("-libraryPath", rootProject.file("core/target/${rustIos.getValue(target.name)}/release").absolutePath)
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/firebaseConfig"))
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.cmp.icons.extended)
                implementation(libs.jb.lifecycle.viewmodel.compose)
                implementation(libs.jb.lifecycle.runtime.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.ktor)
            }
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation("${libs.jna.get()}@aar")
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        androidUnitTest.dependencies {
            implementation(libs.junit)
            // JNA с нативной частью для ПК: тест вызывает Rust-ядро (core/target/debug).
            implementation(libs.jna)
        }
    }
}

android {
    namespace = "ua.chudo.shared"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.resources {
    packageOfResClass = "ua.chudo.shared.res"
    publicResClass = false
}

/**
 * Ключ Firebase (REST) и id проекта — из app/google-services.json (в git не попадает).
 * Нет файла — константы пустые, приложение покажет «Вхід не підключено».
 */
val genFirebaseConfig by tasks.registering {
    val src = rootProject.file("app/google-services.json")
    val out = layout.buildDirectory.file("generated/firebaseConfig/ua/chudo/shared/auth/FirebaseConfig.kt")
    inputs.files(src).optional()
    outputs.file(out)
    @Suppress("UNCHECKED_CAST")
    doLast {
        var key = ""
        var project = ""
        if (src.exists()) {
            val json = JsonSlurper().parse(src) as Map<String, Any?>
            project = ((json["project_info"] as Map<String, Any?>)["project_id"] as String?).orEmpty()
            val client = (json["client"] as List<Map<String, Any?>>).first()
            key = ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String?).orEmpty()
        }
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package ua.chudo.shared.auth\n\n" +
                    "/** Сгенерировано из app/google-services.json (shared/build.gradle.kts). */\n" +
                    "internal object FirebaseConfig {\n" +
                    "    const val API_KEY = \"$key\"\n" +
                    "    const val PROJECT_ID = \"$project\"\n" +
                    "}\n",
            )
        }
    }
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach { dependsOn(genFirebaseConfig) }
