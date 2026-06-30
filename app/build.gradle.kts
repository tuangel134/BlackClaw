import org.jetbrains.kotlin.konan.properties.hasProperty
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
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
            val props = Properties().apply {
                rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
            }
            fun readSigningValue(key: String): String {
                return System.getenv(key)?.takeIf { it.isNotBlank() }
                    ?: props.getProperty(key, "").trim()
            }
            val keystorePath = readSigningValue("KEYSTORE_FILE")
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
        versionCode = readLocalOrEnvInt("BLACKCLAW_VERSION_CODE", 100)
        versionName = readLocalOrEnvString("BLACKCLAW_VERSION_NAME", "1.0.0")
        buildConfigField("String", "VERSION_INFO", getVersionGit())
        buildConfigField("String", "APP_ORIGIN", "\"BlackClaw by BlackClaw | github.com/tuangel134/BlackClaw\"")
        buildConfigField("String", "BUILD_FINGERPRINT", "\"${getBuildFingerprint()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.gson)


    implementation(libs.oapi.sdk)
    implementation(libs.dingtalk)


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
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.utilcode)
    implementation(libs.ok2curl)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.mmkv)
    implementation(libs.adapter)
    implementation(libs.glide)
    implementation(libs.glide.transformations)
    implementation(libs.easyfloat)


    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // LiteRT-LM on-device LLM inference (Google AI Edge)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.0")

    // ML Kit Text Recognition (offline OCR for screen capture / games / SurfaceView)
    // Latin script bundled — covers ES/EN/most European languages. ~10 MB.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Shizuku — optional bridge to ADB-level shell access via the user's
    // pre-installed Shizuku app. Lets us run `input tap`, `am force-stop`, etc.
    // ~10x faster than accessibility gestures and works inside games.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // BouncyCastle es traído transitive por libadb-android (bcprov-jdk15to18).
    // No declaramos bcprov-jdk18on aquí para evitar duplicate-class errors.
    // Solo necesitamos bcpkix para el self-signed cert builder; tomamos la
    // versión que pega con la bcprov de libadb (1.81).
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")

    // libadb-android — wireless-debug pairing (TLS 1.3 + SPAKE2) implemented
    // in pure Java. Esta libra también incluye AdbConnection / shell exec, así
    // que la usamos para TODO el ciclo: pair, connect, run shell. Maintained,
    // ya en producción en App Manager (>1M installs).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    // Conscrypt (standalone) — CRÍTICO para el pairing. libadb hace reflection
    // a exportKeyingMaterial(); la conscrypt del PLATAFORMA (com.android.org.
    // conscrypt) cambia de firma entre fabricantes/versiones (p.ej. MagicOS 10
    // / Android 15 lanza NoSuchMethodException). Con la conscrypt empaquetada,
    // libadb detecta isCustomConscrypt()=true y usa org.conscrypt.Conscrypt,
    // cuya firma es estable. App Manager hace exactamente esto.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // ZXing 二维码/条形码扫描
    implementation(libs.zxing)

    // NanoHTTPD 嵌入式 HTTP 服务器（局域网配置服务）
    implementation(libs.nanohttpd)

    // JSch (maintained fork) — pure-Java SSH client for remote PC/server control.
    // No external binaries needed (unlike sshpass/ssh). Works on all Android versions.
    implementation(libs.jsch)


    testImplementation(libs.junit)
    // Real org.json for unit tests (Android's is a stub that returns defaults).
    testImplementation("org.json:json:20240303")
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

fun getVersionGit(): String {
    val process1 = Runtime.getRuntime().exec("git rev-parse --abbrev-ref HEAD")
    val reader1 = BufferedReader(InputStreamReader(process1.inputStream))
    val branch = reader1.readLine()?.trim()
    reader1.close()

    val process2 = Runtime.getRuntime().exec("git rev-parse HEAD")
    val reader2 = BufferedReader(InputStreamReader(process2.inputStream))
    val sha1 = reader2.readLine()?.trim()
    reader2.close()
    // 将数据拼接起来，如果只需要SHA-1 那么就可以不执行process1命令
    return "\"" + branch + "_" + sha1 + "\""
}

fun getBuildFingerprint(): String {
    val gitHash = try {
        val p = Runtime.getRuntime().exec("git rev-parse --short HEAD")
        val r = BufferedReader(InputStreamReader(p.inputStream))
        r.readLine()?.trim() ?: "unknown"
    } catch (_: Exception) { "unknown" }
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    val builder = System.getenv("BUILDER_ID") ?: System.getProperty("user.name") ?: "local"
    return "$gitHash|$ts|$builder"
}

fun getDateTime(): String {
    val df = SimpleDateFormat("yyyyMMdd_HHmmss");
    return df.format(Date());
}

fun getParameter(key: String, defaultValue: String): String {
    var value = defaultValue
    val hasProperty = project.hasProperty(key)
    if (hasProperty) {
        val property = project.properties[key] as String?
        if (!property.isNullOrEmpty()) {
            value = property
            println("get property[$key]from project:$value")
            return value
        }
    }
    val localPropertiesFile = project.rootProject.file("local.properties")
    val localProperties = Properties()
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        val hasLocalProperty = localProperties.hasProperty(key)
        if (hasLocalProperty) {
            val property = localProperties[key] as String?
            if (!property.isNullOrEmpty()) {
                value = property
                println("get property[$key]from local:$value")
                return value
            }
        }
    }
    println("get property[$key] from default:$value")
    return value
}
