plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.kotlin.serialization)
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
        // Тот же пакет, что у старого APK: ставится поверх старой версии.
        applicationId = "com.chudoostriv.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "3.3.13"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }

        // Де дані: supabase (за замовчуванням) | firestore (відкат: -Pbackend=firestore або змінна BACKEND). Вхід — завжди Firebase Auth.
        val backend = (findProperty("backend") as String? ?: System.getenv("BACKEND")).orEmpty().trim().lowercase().ifEmpty { "supabase" }
        require(backend in setOf("firestore", "supabase")) { "backend: firestore | supabase, а не $backend" }
        buildConfigField("String", "BACKEND", "\"$backend\"")
        val gateway = providers.gradleProperty("deviceGatewayUrl").orNull.orEmpty().trim()
        require(gateway.isEmpty() || (backend == "supabase" &&
            Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._/-]*)?").matches(gateway)))
        buildConfigField("String", "DEVICE_GATEWAY_URL", "\"$gateway\"")
        // Публічні: URL проєкту й publishable key (доступ вирішують RLS-політики, supabase/migrations).
        buildConfigField("String", "SUPABASE_URL", "\"https://wbboztsxzuxfocdqjyzk.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_pXZn08SVg5og-I9_PuX5LA_qdTmydyk\"")
    }

    // Постійний ключ підпису: без нього кожна збірка CI підписана новим debug-ключем, і Android не ставить
    // нову версію поверх старої (а отже, й оновлення із застосунку). Ключ — у секретах GitHub
    // (ANDROID_KEYSTORE_B64 → файл, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS), у git його немає.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            // trim(): секрети, записані з Windows, бувають із \r\n у кінці.
            val pw = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.trim()
            storePassword = pw
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")?.trim()?.ifEmpty { null } ?: "chudoskan"
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.trim()?.ifEmpty { null } ?: pw
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Постійний ключ, якщо є; інакше (локальна збірка) — debug.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        // Обновления зависимостей — отдельной задачей, а не ошибкой сборки.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
        warningsAsErrors = true
    }
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
    implementation(libs.guava)

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
    testImplementation(libs.junit)
    // Тест: штрихкод из LabelRenderer читается как тот же EAN-13.
    testImplementation(libs.zxing.core)
    // Тест імпорту xlsx: той самий XmlPullParser (kXML), що й android.util.Xml, але без Android.
    testImplementation(libs.kxml2)

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
    // Дані в Supabase (Postgres): без плагіна Auth — токен дає Firebase.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation("${libs.jna.get()}@aar")
}
