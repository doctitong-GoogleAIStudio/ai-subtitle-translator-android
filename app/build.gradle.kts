// AGP 9 compiles Kotlin itself (built-in Kotlin), so no Kotlin plugin is applied here.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aivicventures.subtitletranslator"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.aivicventures.subtitletranslator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // One universal APK with every ABI.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild {
            cmake {
                // 16 KB page alignment for every native library (Android 15+ devices).
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        release {
            // Phase 1: signed with the local debug key so the APK installs; a release key comes later.
            signingConfig = signingConfigs.getByName("debug")
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
}

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
    // Nullness annotations used by the vendored Media3 FFmpeg extension (MIT; compile time only).
    compileOnly("org.checkerframework:checker-qual:3.49.0")
    testImplementation(libs.junit)
}
