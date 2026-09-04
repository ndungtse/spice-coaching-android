plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("maven-publish")
}

// sherpa-onnx Android .aar lives in :sdk-android-sherpa, which explodes it into
// classes.jar + jniLibs so AGP doesn't trip on local-.aar-in-library
// restrictions. :sdk-android stays sherpa-free; hosts opt in by including
// :sdk-android-sherpa and calling Builder.offlineSttEngineFactory(SherpaOnnxStt.factory).

android {
    namespace = "com.medtroniclabs.microcoaching"
    compileSdk = 36

    defaultConfig {
        // Matches SPICE 2.0. The inference engine declares minSdk 24, so the SDK manifest
        // overrides it — see the AndroidManifest comment for why that is safe.
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "SDK_VERSION", "\"0.6.0-SNAPSHOT\"")
        // HF_TOKEN intentionally NOT baked into the SDK. The published .aar must
        // not ship a HuggingFace token; host apps that need one pass it at runtime
        // via MicroCoachingSDK.Builder.huggingFaceToken(). Models in the public
        // `litert-community` HF org download without auth.

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Prevent ONNX/model files from being compressed in the APK
    androidResources {
        noCompress += listOf("bin", "onnx", "tflite", "litertlm")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
        }
        jniLibs {
            keepDebugSymbols += "*/arm64-v8a/*.so"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.medtroniclabs.microcoaching"
                artifactId = "sdk-android"
                version = "0.6.0-SNAPSHOT"
            }
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material) // BottomSheetDialogFragment + Material widgets
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.startup)

    // Markdown parsing for module card body content. Custom Compose renderer
    // lives in com.medtroniclabs.microcoaching.ui.markdown.
    implementation("org.jetbrains:markdown:0.7.3")

    // Coil — images in rich (TipTap) card bodies. Compose renderer lives in
    // com.medtroniclabs.microcoaching.ui.richtext.
    implementation(libs.coil.compose)

    // Media3 / ExoPlayer — fullscreen video playback for rich card bodies
    // (com.medtroniclabs.microcoaching.ui.video.VideoPlayerActivity).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // PDF viewing uses the platform android.graphics.pdf.PdfRenderer (API 21+)
    // in ui.document.PdfPagerScreen — no third-party PDF library. This drops the
    // former pdfium-based android-pdf-viewer dependency, which bundled ~7.7 MB/ABI
    // of native .so files (libpdfium, libicuuc, chromium libc++/zlib).

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Note: Hilt is intentionally excluded from the SDK library.
    // SDKs should not force a DI framework on the host app.
    // SPICE can optionally add a Hilt provider binding for CoachingDataRepository
    // using MicroCoachingSDK.getInstance().dataRepository in its AppModule.

    // Room DB
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // OpenTelemetry
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.exporter.logging)
    // Note: opentelemetry-semconv omitted for now — attribute keys are inlined as strings
    // Add back when a stable semconv release is available

    // LiteRT-LM — the SDK's only on-device inference engine. Every model in the
    // catalog is a `.litertlm`, so one runtime covers the default and every fallback.
    implementation(libs.litertlm.android) {
        // Only the engine's tool-calling entry points (ReflectionTool, ToolKt) reach
        // kotlin-reflect, and chat constructs neither — it sends one grounded prompt and
        // reads the answer. Class loading is lazy, so those classes never link and the
        // missing dependency never resolves. Worth a couple of MiB of minified dex.
        // Using tool calling means dropping this exclusion first.
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

    // LiteRT — the TFLite interpreter behind the EmbeddingGemma query encoder. A
    // separate runtime from LiteRT-LM above: that one loads `.litertlm` chat bundles,
    // this one runs a plain `.tflite` graph. No EmbeddingGemma `.litertlm` exists, so
    // the two cannot be collapsed into one.
    implementation(libs.litert)

    // ML Kit on-device translation (EN→BN, ~20 MB language pack downloaded on demand)
    implementation(libs.mlkit.translate)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    // Apache Commons Compress — BZip2 + Tar parsers for sherpa-onnx model
    // archives (.tar.bz2). Used only by SttModelDownloadWorker.
    implementation(libs.commons.compress)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

/**
 * Local retrieval lab: runs the real offline-chat ranking stack over a static corpus
 * file so it can be exercised from a browser without a device or synced backend data.
 *
 *   MC_CORPUS=path/to/modules.json ./gradlew :sdk-android:retrievalLab
 */
tasks.register<JavaExec>("retrievalLab") {
    group = "verification"
    description = "Serve the offline retrieval pipeline on http://127.0.0.1:7171 for tools/retrieval-lab"
    // Reuse the unit-test runtime classpath AGP already assembled: resolving that
    // configuration by hand hits Android variant ambiguity.
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin", "compileDebugUnitTestJavaWithJavac")
    classpath = files({ unitTest.get().classpath })
    mainClass.set("com.medtroniclabs.microcoaching.ai.retrieval.DevRetrievalServer")
    // corpus paths in the docs are written relative to the repo root
    workingDir = rootDir
    // Defaults to the corpus snapshot committed for the retrieval tests, so the lab
    // starts with no environment set up.
    environment(
        "MC_CORPUS",
        System.getenv("MC_CORPUS")
            ?: "sdk-android/src/test/resources/retrieval/audit_corpus_2026-08.json",
    )
    environment("MC_LAB_PORT", System.getenv("MC_LAB_PORT") ?: "7171")
}
