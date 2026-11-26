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

    // Веб-версия для браузера (в т.ч. iPhone без App Store): тот же UI, ядро — core.wasm.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("chudoscan")
        browser {
            commonWebpackConfig { outputFileName = "chudoscan.js" }
        }
        binaries.executable()
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
        wasmJsMain {
            resources.srcDir(layout.buildDirectory.dir("generated/coreWasm"))
            dependencies {
                implementation(libs.ktor.client.js)
            }
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
    // Де дані: firestore | supabase (-Pbackend=… або BACKEND, як app/). Вхід — завжди Firebase.
    val backend = (findProperty("backend") as String? ?: System.getenv("BACKEND")).orEmpty().trim().lowercase().ifEmpty { "supabase" }
    require(backend in setOf("firestore", "supabase")) { "backend: firestore | supabase, а не $backend" }
    inputs.files(src).optional()
    inputs.property("backend", backend)
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
                    "    const val BACKEND = \"$backend\"\n" +
                    // Публічні: URL проєкту й publishable key (доступ вирішують RLS-політики).
                    "    const val SUPABASE_URL = \"https://wbboztsxzuxfocdqjyzk.supabase.co\"\n" +
                    "    const val SUPABASE_KEY = \"sb_publishable_pXZn08SVg5og-I9_PuX5LA_qdTmydyk\"\n" +
                    "}\n",
            )
        }
    }
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach { dependsOn(genFirebaseConfig) }

// Rust-ядро для браузера: core/ → wasm32 → ресурсы веб-версии (chudo_core.wasm рядом с index.html).
val buildCoreWasm by tasks.registering(Exec::class) {
    group = "rust"
    description = "Собирает chudo-core в WebAssembly"
    workingDir = rootProject.file("core")
    commandLine("cargo", "build", "--lib", "--release", "--target", "wasm32-unknown-unknown")
    onlyIf { providers.gradleProperty("skipRust").orNull != "true" }
}
val copyCoreWasm by tasks.registering(Copy::class) {
    dependsOn(buildCoreWasm)
    from(rootProject.file("core/target/wasm32-unknown-unknown/release/chudo_core.wasm"))
    into(layout.buildDirectory.dir("generated/coreWasm"))
}
tasks.matching { it.name == "wasmJsProcessResources" }.configureEach { dependsOn(copyCoreWasm) }
