plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Вход сотрудников и остатки магазинов — Firebase. Файл google-services.json кладётся в app/
// (скачивается из консоли Firebase, в git не попадает). Без него приложение соберётся,
// но экран входа попросит подключить Firebase.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "ua.chudo.scanner"
    compileSdk = 36

    defaultConfig {
        // Тот же пакет, что у старого APK: ставится поверх и сохраняет ключ OpenAI.
        applicationId = "com.chudoostriv.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Для личного APK подписываем debug-ключом; для Play — свой keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Rust-ядро → app/src/main/jniLibs/<abi>/libchudo_core.so (нужен cargo-ndk и NDK).
val buildRust by tasks.registering(Exec::class) {
    group = "rust"
    description = "Собирает chudo-core для Android через cargo-ndk"
    workingDir = rootProject.file("core")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
        "build", "--release",
    )
}

if (providers.gradleProperty("skipRust").orNull != "true") {
    tasks.named("preBuild") { dependsOn(buildRust) }
}

dependencies {
    // Firebase тянет Guava только в рантайм, а на этапе компиляции оставляет пустую заглушку
    // listenablefuture:9999.0 — CameraX тогда не видит ListenableFuture. Та же версия, что у Firebase.
    implementation("com.google.guava:guava:32.1.3-android")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.datastore.preferences)
    implementation("${libs.jna.get()}@aar")
}
