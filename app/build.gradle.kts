import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun readLocalOrEnvString(key: String, defaultValue: String = ""): String {
    val props = Properties().apply {
        File("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    return System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: props.getProperty(key, defaultValue).trim()
}

fun readLocalOrEnvInt(key: String, defaultValue: Int): Int {
    return readLocalOrEnvString(key).toIntOrNull() ?: defaultValue
}

android {
    namespace = "com.blackclaw.android"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        create("release") {
            // Signing values resolve from (in priority): env vars → signing/keystore.properties
            // (the private keys repo dropped into the project) → local.properties.
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }
                ?.inputStream()?.use { props.load(it) }
            val signingProps = rootProject.file("signing/keystore.properties")
            if (signingProps.exists()) signingProps.inputStream().use { props.load(it) } // overrides
            fun readSigningValue(key: String): String {
                return System.getenv(key)?.takeIf { it.isNotBlank() }
                    ?: props.getProperty(key, "").trim()
            }
            var keystorePath = readSigningValue("KEYSTORE_FILE")
            // When using the signing/ folder without an explicit path, default to
            // the bundled keystore next to keystore.properties.
            if (keystorePath.isEmpty() && signingProps.exists()) {
                val bundled = rootProject.file("signing/blackclaw-release.jks")
                if (bundled.exists()) keystorePath = bundled.absolutePath
            }
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = readSigningValue("KEYSTORE_PASSWORD")
                keyAlias = readSigningValue("KEY_ALIAS")
                keyPassword = readSigningValue("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.blackclaw.android"
        minSdk = 28
        targetSdk = 36
        versionCode = readLocalOrEnvInt("BLACKCLAW_VERSION_CODE", 114)
        versionName = readLocalOrEnvString("BLACKCLAW_VERSION_NAME", "1.2.0")
        buildConfigField("String", "VERSION_INFO", getVersionGit())
        buildConfigField("String", "APP_ORIGIN", "\"BlackClaw by BlackClaw | github.com/tuangel134/BlackClaw\"")
        buildConfigField("String", "BUILD_FINGERPRINT", "\"${getBuildFingerprint()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        getByName("debug") {
            // No proguardFiles here on purpose: with minify + shrink off R8 never
            // runs, so declaring them would be dead config that misleads anyone
            // editing proguard-rules.pro into thinking debug validates the rules.
            // Only `release` exercises them.
            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // android.util.Log and other framework stubs return defaults instead
            // of throwing "Method ... not mocked", so pure-logic classes that
            // happen to log (XLog) can be unit-tested on the JVM.
            isReturnDefaultValues = true
            // Robolectric needs the real merged resources and manifest to inflate a
            // theme; without this it cannot start an activity to host a composable.
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // ── APK size: split per-ABI so users download only their architecture. ──
    // The on-device LLM (liblitertlm_jni.so ~24MB) and MMKV ship 64-bit only,
    // so 32-bit ABIs can't run inference anyway — we target the two 64-bit ABIs
    // (arm64 for real phones, x86_64 for emulators) and keep a universal APK as
    // a safe fallback. This cuts the per-arch APK from ~217MB to roughly half.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // PRoot is launched as a process, so it must be extracted by Android into
        // applicationInfo.nativeLibraryDir (the only executable app-owned location
        // on Android 10+).  The rest of the fixed Linux environment stays in assets.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    androidResources {
        noCompress += "zip"
    }
}

/**
 * Keep the Android-only Conscrypt out of the JVM test classpath.
 *
 * The app bundles `conscrypt-android` on purpose (libadb needs a Conscrypt whose
 * signatures do not shift between vendor Android builds). Robolectric brings
 * `conscrypt-openjdk-uber`, the same classes built for the host. Both end up on the unit
 * test runtime classpath, the Android one wins by ordering, and its `NativeCryptoJni`
 * then looks for a `conscrypt_jni` shared library that only exists on a device — so
 * every Robolectric test dies in setup with `UnsatisfiedLinkError` before reaching its
 * own assertions.
 *
 * Excluding it here rather than dropping the dependency: on a device the Android build is
 * the correct one, and the tests never exercise ADB's TLS path anyway.
 */
configurations.configureEach {
    if (name.contains("UnitTestRuntimeClasspath")) {
        exclude(group = "org.conscrypt", module = "conscrypt-android")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.gson)

    // LangChain4j (exclude JDK http-client, use OkHttp adapter for Android)
    implementation(libs.langchain4j.core)
    implementation(libs.langchain4j.openai) {
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }
    implementation(libs.langchain4j.anthropic) {
        exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
    }
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.utilcode)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.mmkv)
    implementation(libs.glide)
    implementation(libs.easyfloat)


    // Jetpack Compose — the BOM supplies versions for the unversioned artifacts
    // below, so those stay as plain coordinates on purpose.
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.activity.compose)
    implementation(libs.androidx.media)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // LiteRT-LM on-device LLM inference (Google AI Edge)
    implementation(libs.litertlm)

    // ML Kit Text Recognition (offline OCR for screen capture / games / SurfaceView)
    // Latin script bundled — covers ES/EN/most European languages. ~10 MB.
    implementation(libs.mlkit.text.recognition)

    // Direct .zim reader: modern archives use Zstandard; older ones use XZ/LZMA.
    // Both dependencies are permissively licensed and avoid bundling GPL libzim.
    implementation(libs.zstd.jni)
    implementation(libs.xz)
    // Streaming TAR reader for the signed, versioned Linux rootfs bootstrap.
    implementation("org.apache.commons:commons-compress:1.26.2")

    // Shizuku — optional bridge to ADB-level shell access via the user's
    // pre-installed Shizuku app. Lets us run `input tap`, `am force-stop`, etc.
    // ~10x faster than accessibility gestures and works inside games.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // BouncyCastle es traído transitive por libadb-android (bcprov-jdk15to18).
    // No declaramos bcprov-jdk18on aquí para evitar duplicate-class errors.
    // Solo necesitamos bcpkix para el self-signed cert builder; tomamos la
    // versión que pega con la bcprov de libadb (1.81).
    implementation(libs.bcpkix)

    // libadb-android — wireless-debug pairing (TLS 1.3 + SPAKE2) implemented
    // in pure Java. Esta libra también incluye AdbConnection / shell exec, así
    // que la usamos para TODO el ciclo: pair, connect, run shell. Maintained,
    // ya en producción en App Manager (>1M installs).
    implementation(libs.libadb.android)
    // No direct imports, but libadb-android reaches into sun.security.* at
    // runtime for the pairing certificate path — keep it declared.
    implementation(libs.sun.security.android)

    // Conscrypt (standalone) — CRÍTICO para el pairing. libadb hace reflection
    // a exportKeyingMaterial(); la conscrypt del PLATAFORMA (com.android.org.
    // conscrypt) cambia de firma entre fabricantes/versiones (p.ej. MagicOS 10
    // / Android 15 lanza NoSuchMethodException). Con la conscrypt empaquetada,
    // libadb detecta isCustomConscrypt()=true y usa org.conscrypt.Conscrypt,
    // cuya firma es estable. App Manager hace exactamente esto.
    implementation(libs.conscrypt.android)

    // ZXing 二维码/条形码扫描
    implementation(libs.zxing)

    // NanoHTTPD 嵌入式 HTTP 服务器（局域网配置服务）
    implementation(libs.nanohttpd)

    // JSch (maintained fork) — pure-Java SSH client for remote PC/server control.
    // No external binaries needed (unlike sshpass/ssh). Works on all Android versions.
    implementation(libs.jsch)

    // Vosk — fully offline speech recognition / keyword spotting. No API key, no
    // account, no audio leaves the device. Used for the always-listening wake
    // word (no system beep, unlike SpeechRecognizer). The Spanish model ships
    // bundled in assets (vosk-model-es.zip); other languages (e.g. English) are
    // downloaded on demand from the voice settings.
    implementation(libs.vosk.android)

    // Android for Cars App Library — drives the hands-free Android Auto experience
    // (voice-first quick actions on the car head unit / phone projection). The
    // "projected" artifact adds the Android Auto host backend.
    implementation(libs.car.app)
    implementation(libs.car.app.projected)


    testImplementation(libs.junit)
    // Real org.json for unit tests (Android's is a stub that returns defaults).
    testImplementation("org.json:json:20240303")

    // ── Layout tests on the JVM ──
    // Robolectric runs the Android framework in-process, so a Compose screen can be
    // measured without a device or emulator. This exists because three layout bugs
    // shipped that no amount of pure-logic testing could catch: a top bar whose title
    // overflowed, a hero card that swallowed the viewport, and a title crushed by a
    // button beside it. Those are measurement failures, and measurement needs a real
    // layout pass.
    // Versions pinned exactly rather than as ranges, so a test suite cannot start
    // behaving differently without the change appearing in this file.
    //
    // 4.16.1 specifically: Robolectric maps each SDK level to a prebuilt Android
    // runtime, and the project targets API 36, which earlier releases have no build
    // for. On 4.14 the tests do not fail with a clear message — resolution stalls
    // looking for an artifact that does not exist.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Supplies the empty activity ui-test hosts composables in. Must be debug-only:
    // it injects an activity into the manifest that has no business in a release APK.
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

tasks.register("injectBuildFingerprint") {
    doLast {
        val gitHash = try {
            val p = Runtime.getRuntime().exec("git rev-parse HEAD")
            val r = BufferedReader(InputStreamReader(p.inputStream))
            r.readLine()?.trim() ?: "unknown"
        } catch (_: Exception) { "unknown" }
        val ts = System.currentTimeMillis()
        val builder = System.getenv("BUILDER_ID") ?: System.getProperty("user.name") ?: "local"
        val fp = "t=$ts\nc=$gitHash\nb=$builder\nv=${android.defaultConfig.versionName}"
        val hexEncoded = fp.toByteArray().joinToString("") { "%02x".format(it) }
        file("src/main/assets/.pcfp").apply {
            parentFile.mkdirs()
            writeText(hexEncoded)
        }
    }
}
tasks.named("preBuild") { dependsOn("injectBuildFingerprint") }

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                val versionName = android.defaultConfig.versionName ?: "0.0.0"
                // Include the ABI (or "universal") in the filename so the per-ABI
                // split APKs don't overwrite each other in the output dir.
                val abi = output.filters
                    .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                    ?.identifier ?: "universal"
                val fileName = "BlackClaw_v${versionName}_${abi}_${getDateTime()}.apk"
                println("output file name: $fileName")
                output.outputFileName.set(fileName)
            }
        }
    }
}

// Best-effort git metadata. Building from a source tarball (or any non-git
// checkout) has no `git` and no .git dir, so every call is guarded and falls
// back to "unknown" instead of failing Gradle configuration outright.
fun gitOutput(command: String): String = try {
    val p = Runtime.getRuntime().exec(command)
    BufferedReader(InputStreamReader(p.inputStream)).use { it.readLine()?.trim() } ?: "unknown"
} catch (_: Exception) {
    "unknown"
}

fun getVersionGit(): String {
    val branch = gitOutput("git rev-parse --abbrev-ref HEAD")
    val sha1 = gitOutput("git rev-parse HEAD")
    // 将数据拼接起来，如果只需要SHA-1 那么就可以不执行process1命令
    return "\"" + branch + "_" + sha1 + "\""
}

fun getBuildFingerprint(): String {
    val gitHash = gitOutput("git rev-parse --short HEAD")
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    val builder = System.getenv("BUILDER_ID") ?: System.getProperty("user.name") ?: "local"
    return "$gitHash|$ts|$builder"
}

fun getDateTime(): String {
    val df = SimpleDateFormat("yyyyMMdd_HHmmss");
    return df.format(Date());
}
