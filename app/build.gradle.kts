import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Offline Arabic speech model (Vosk, Apache-2.0).
// The model is packaged INSIDE the APK as an asset. Because it is larger than
// GitHub's 100 MB file limit it is not committed; this task takes it from
// <root>/models/ (if present) or downloads it once at build time, verifies the
// SHA-256 and unpacks it into generated assets. The phone never downloads it.
// ---------------------------------------------------------------------------
val voskModelName = "vosk-model-small-ar-tn-0.1-linto"
val voskModelSha256 = "dec314da301a22ceee91a9c7121f469e17f6be9dbb6fce8c3d37739a20d16b38"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelName.zip"
val modelAssetDir = "model-ar"
val generatedAssets = layout.buildDirectory.dir("generated/voskAssets")

val prepareVoskModel by tasks.registering {
    description = "Verifies and unpacks the bundled offline Arabic Vosk model into assets"
    val outDir = generatedAssets
    val zipFile = rootProject.file("models/$voskModelName.zip")
    outputs.dir(outDir)
    inputs.property("sha", voskModelSha256)
    doLast {
        if (!zipFile.exists()) {
            zipFile.parentFile.mkdirs()
            logger.lifecycle("Downloading $voskModelUrl")
            URI(voskModelUrl).toURL().openStream().use { input ->
                zipFile.outputStream().use { input.copyTo(it) }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(zipFile).use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == voskModelSha256) { "Model checksum mismatch: $actual" }

        val target = outDir.get().asFile.resolve(modelAssetDir)
        target.deleteRecursively()
        target.mkdirs()
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = target.resolve(rel)
                check(f.canonicalPath.startsWith(target.canonicalPath)) { "Bad zip entry ${e.name}" }
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile.mkdirs(); f.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        // Version marker used by the app to decide whether to re-unpack on update.
        target.resolve("MODEL_VERSION").writeText("$voskModelName:$voskModelSha256\n")
        // List of all files so the app can unpack without AssetManager.list() walking.
        val files = target.walkTopDown().filter { it.isFile }.map { it.relativeTo(target).invariantSeparatorsPath }.sorted()
        target.resolve("FILES").writeText(files.joinToString("\n") + "\n")
    }
}

// ---------------------------------------------------------------------------
// Whisper Turbo (OpenAI, MIT licence) for understanding commands, run on the phone
// with sherpa-onnx (Apache-2.0). Same approach: taken from <root>/models/ or
// downloaded once at build time from the official sherpa-onnx GitHub releases,
// SHA-256 verified, and packaged inside the APK.
// ---------------------------------------------------------------------------
val sherpaVersion = "v1.13.8"
val sherpaSha256 = "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
val sherpaUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$sherpaVersion/sherpa-onnx-$sherpaVersion-android.tar.bz2"
val whisperSha256 = "b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22"
val whisperUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-turbo.tar.bz2"
val whisperAssets = layout.buildDirectory.dir("generated/whisperAssets")
val sherpaJni = layout.buildDirectory.dir("generated/sherpaJni")
val keptAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

fun fetchVerified(file: File, url: String, sha: String) {
    if (!file.exists()) {
        file.parentFile.mkdirs()
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { s ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual == sha) { "Checksum mismatch for ${file.name}: $actual" }
}

/**
 * Extracts [members] of a .tar.bz2 into [dir] with the system `tar`
 * (available on Linux, macOS and Windows 10+).
 */
fun untarBz2(archive: File, dir: File, members: List<String>) {
    dir.mkdirs()
    val p = ProcessBuilder(listOf("tar", "-xjf", archive.absolutePath, "-C", dir.absolutePath) + members)
        .redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "tar failed: $out" }
}

val prepareWhisper by tasks.registering {
    description = "Verifies and unpacks Whisper Turbo (int8) and the sherpa-onnx native libraries"
    outputs.dir(whisperAssets)
    outputs.dir(sherpaJni)
    inputs.property("sha", sherpaSha256 + whisperSha256)
    doLast {
        val sherpaTar = rootProject.file("models/sherpa-android.tar.bz2")
        val whisperTar = rootProject.file("models/whisper-turbo.tar.bz2")
        fetchVerified(sherpaTar, sherpaUrl, sherpaSha256)
        fetchVerified(whisperTar, whisperUrl, whisperSha256)

        val jniOut = sherpaJni.get().asFile.apply { deleteRecursively(); mkdirs() }
        val tmp = temporaryDir.resolve("sherpa").apply { deleteRecursively() }
        untarBz2(sherpaTar, tmp, keptAbis.flatMap { listOf("./jniLibs/$it/libsherpa-onnx-jni.so", "./jniLibs/$it/libonnxruntime.so") })
        tmp.resolve("jniLibs").copyRecursively(jniOut, overwrite = true)

        val wOut = whisperAssets.get().asFile.resolve("whisper").apply { parentFile.deleteRecursively(); mkdirs() }
        val wanted = listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt")
        val wTmp = temporaryDir.resolve("whisper").apply { deleteRecursively() }
        untarBz2(whisperTar, wTmp, wanted.map { "sherpa-onnx-whisper-turbo/$it" })
        wanted.forEach { wTmp.resolve("sherpa-onnx-whisper-turbo/$it").copyTo(wOut.resolve(it), overwrite = true) }
        wTmp.deleteRecursively(); tmp.deleteRecursively()
        check(wanted.all { wOut.resolve(it).exists() }) { "Whisper files missing" }
    }
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.haitham.voiceassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haitham.voiceassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "2.0.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // JNA/Vosk use reflection heavily; shrinking gives little gain next to the
            // ~290 MB model, so it is disabled to avoid runtime breakage.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    sourceSets["main"].assets.srcDir(generatedAssets)
    sourceSets["main"].assets.srcDir(whisperAssets)
    sourceSets["main"].jniLibs.srcDir(sherpaJni)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources {
        // The Whisper ONNX files are read straight from the APK (no copy on the phone),
        // which needs them stored uncompressed (they barely compress anyway).
        noCompress += listOf("onnx")
    }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.named("preBuild") { dependsOn(prepareVoskModel, prepareWhisper) }

dependencies {
    // Offline speech recognition (Apache-2.0) + JNA (Apache-2.0 / LGPL-2.1 dual)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
