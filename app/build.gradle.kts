import java.net.URI
import java.security.MessageDigest
import java.util.Properties

// AGP 9 compiles Kotlin itself (built-in Kotlin), so no Kotlin plugin is applied here.
plugins {
    alias(libs.plugins.android.application)
}

val bundledModels = rootProject.layout.projectDirectory.dir("bundled-models")
val ffmpegSourceDir = layout.buildDirectory.dir("generated/ffmpegSource").get().asFile

android {
    namespace = "com.aivicventures.subtitletranslator"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.aivicventures.subtitletranslator"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.1"

        // One universal APK with every ABI.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild {
            cmake {
                // 16 KB page alignment for every native library (Android 15+ devices).
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    // Release key from keystore.properties (git-ignored, see README "Release signing");
    // without it the release build falls back to the debug key so it still installs.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // ggml loads its CPU backend variants with dlopen from the native library folder,
        // so the libraries must be extracted to disk at install time.
        jniLibs.useLegacyPackaging = true
    }
    buildFeatures { buildConfig = true }
    // Optional LGPL FFmpeg shared libraries from scripts/build_ffmpeg.sh (git-ignored).
    sourceSets.getByName("main").jniLibs.srcDir("src/main/ffmpeg/jniLibs")
    // ...and their LGPL source (assets/source, from ffmpegSource below).
    sourceSets.getByName("main").assets.srcDir(ffmpegSourceDir)
    // Every model of the catalog ships in the APK (assets/models, from fetchModels below).
    sourceSets.getByName("main").assets.srcDir(bundledModels.dir("assets"))
    // Stored uncompressed so whisper.cpp and CTranslate2 read the weights in place from the APK
    // (and the already compressed FFmpeg source zip is not compressed a second time).
    androidResources { noCompress += listOf("bin", "zip") }
}

// Fetches every model in assets/model_catalog.json from its pinned URL into
// bundled-models/assets/models/ (git-ignored, kept across clean builds) and checks its
// SHA-256, so the APK contains all of them and the app needs no downloads. Checksum markers
// go to bundled-models/verified/ so they are not packaged.
val fetchModels by tasks.registering {
    val catalog = file("src/main/assets/model_catalog.json")
    val out = bundledModels.dir("assets/models").asFile
    val verified = bundledModels.dir("verified").asFile
    inputs.file(catalog)
    outputs.dir(bundledModels.dir("assets"))
    doLast {
        @Suppress("UNCHECKED_CAST")
        val models = (groovy.json.JsonSlurper().parse(catalog) as Map<String, Any>)["models"] as List<Map<String, Any>>
        for (m in models) {
            @Suppress("UNCHECKED_CAST")
            val files = (m["files"] as List<Map<String, Any>>?)
                ?: listOf(mapOf("path" to m["file"]!!, "url" to m["url"]!!, "size" to m["size"]!!, "sha256" to m["sha256"]!!))
            for (f in files) {
                val target = File(out, f["path"] as String)
                val size = (f["size"] as Number).toLong()
                val sha = f["sha256"] as String
                val ok = File(verified, f["path"] as String + ".ok")
                if (target.length() == size && ok.exists() && ok.readText() == "$size $sha") continue
                target.parentFile.mkdirs()
                if (target.length() != size) {
                    logger.lifecycle("downloading ${target.name} (${size / 1_000_000} MB)")
                    val part = File(verified, f["path"] as String + ".part").apply { parentFile.mkdirs() }
                    URI(f["url"] as String).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
                    target.delete()
                    check(part.renameTo(target)) { "could not write $target" }
                }
                val actual = sha256(target)
                if (target.length() != size || actual != sha) {
                    target.delete()
                    error("${target.name}: checksum mismatch (got $actual), removed; run the build again")
                }
                ok.parentFile.mkdirs()
                ok.writeText("$size $sha")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchModels) }

fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// FFmpeg is LGPL, so its source ships inside the APK next to the libraries built from it:
// assets/source/ffmpeg-<version>-source.zip holds the unmodified release tarball (project root,
// SHA-256 checked against scripts/build_ffmpeg.sh), the configure line of each ABI and the
// build script. The About screen saves a copy. Builds without the FFmpeg libraries skip it.
val ffmpegSource by tasks.registering(Zip::class) {
    val script = rootProject.file("scripts/build_ffmpeg.sh")
    val vars = script.readLines().filter { Regex("^[A-Z0-9_]+=").containsMatchIn(it) }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
    val version = vars.getValue("VERSION")
    val tarball = rootProject.file("ffmpeg-$version.tar.xz")
    val libs = fileTree("src/main/ffmpeg/jniLibs") { include("**/*.so") }
    onlyIf { !libs.isEmpty }
    archiveFileName.set("ffmpeg-$version-source.zip")
    destinationDirectory.set(File(ffmpegSourceDir, "source"))
    from(tarball)
    from(script)
    from("src/main/ffmpeg") { include("configuration-*.txt", "README-source.txt") }
    doFirst {
        check(tarball.isFile) { "${tarball.name} is missing from the project root: the FFmpeg libraries are LGPL and their source must ship with them (scripts/build_ffmpeg.sh downloads it)" }
        check(sha256(tarball) == vars.getValue("SHA256")) { "${tarball.name}: checksum mismatch with scripts/build_ffmpeg.sh" }
    }
}
tasks.named("preBuild") { dependsOn(ffmpegSource) }

// The About screen shows the same notices file as the repository root.
val copyNotices by tasks.registering(Copy::class) {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(layout.projectDirectory.dir("src/main/assets/licenses"))
}
tasks.named("preBuild") { dependsOn(copyNotices) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.inspector)
    implementation(libs.material)
    // Nullness annotations used by the vendored Media3 FFmpeg extension (MIT; compile time only).
    compileOnly("org.checkerframework:checker-qual:3.49.0")
    testImplementation(libs.junit)
}
