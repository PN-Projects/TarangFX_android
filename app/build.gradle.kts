import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Resolves the onnxruntime-android AAR so its C/C++ headers can be extracted for CMake. */
val ortAar: Configuration = configurations.create("ortAar")

// ---------------------------------------------------------------------------------------------
// Versioning. Inputs: version.properties (major line, required) and -PtfxVersionName=X.Y.Z,
// which CI computes from the release tags. Nothing is hard-coded here; CI's version guard fails
// the build if a literal versionName/versionCode appears in this file.
// versionCode = major * 10000 + minor * 100 + patch (minor and patch are 0..99).
// ---------------------------------------------------------------------------------------------
val majorLine: Int = rootProject.file("version.properties").let { f ->
    check(f.exists()) { "version.properties is missing (required by the versioning policy)" }
    Properties().apply { f.inputStream().use { load(it) } }
        .getProperty("major")?.trim()?.toIntOrNull()?.takeIf { it >= 1 }
        ?: error("version.properties: 'major' must be a positive integer")
}
val tfxVersionName: String = (findProperty("tfxVersionName") as String?)?.trim() ?: "$majorLine.0.0-local"
val tfxVersionCode: Int = run {
    val m = Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?""").matchEntire(tfxVersionName)
        ?: error("tfxVersionName must be MAJOR.MINOR.PATCH[-suffix], got '$tfxVersionName'")
    val (maj, min, pat) = m.destructured.toList().take(3).map { it.toInt() }
    check(maj == majorLine) { "Version $tfxVersionName does not belong to major line $majorLine (version.properties)" }
    check(min in 0..99 && pat in 0..99) { "minor and patch must be 0..99, got $tfxVersionName" }
    maj * 10000 + min * 100 + pat
}

android {
    namespace = "com.pnprojects.tarangfx"
    // Compose 1.13 requires the Android 17 QPR1 SDK (API 37, minor level 1).
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.pnprojects.tarangfx"
        minSdk = 26
        targetSdk = 35
        versionCode = tfxVersionCode
        versionName = tfxVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a = phones; x86_64 = emulator. ABI splits are produced automatically in the .aab.
            // `-Pabi=arm64-v8a` builds a phone-only APK (no emulator code, ~40% smaller).
            val abiOverride = (project.findProperty("abi") as String?)?.split(',')?.map { it.trim() }
            abiFilters += abiOverride ?: listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DORT_DIR=" + layout.buildDirectory.dir("ort").get().asFile.absolutePath.replace('\\', '/'),
                )
            }
        }
    }

    // SECURITY: release signing material is read ONLY from environment variables, which CI injects
    // from its secrets manager (e.g. AWS Secrets Manager -> masked env vars) and which
    // tools/release/build-release.ps1 sets locally from a DPAPI-encrypted store. No keystore,
    // password or alias is ever committed. Without the variables the release APK is left unsigned.
    signingConfigs {
        val keystore = System.getenv("TFX_RELEASE_KEYSTORE")
        if (!keystore.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("TFX_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("TFX_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("TFX_RELEASE_KEY_PASSWORD")
                // minSdk 26: APK Signature Scheme v2/v3 only (v1/JAR signing is weaker and unneeded).
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
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
    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // ONNX Runtime is used through its C/C++ API from libtarangfx.so; the Java binding's
            // JNI library is never loaded, so don't ship it (smaller APK, less native attack surface).
            excludes += "**/libonnxruntime4j_jni.so"
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
    androidResources {
        // .onnx models must be stored uncompressed so they can be copied/mapped efficiently.
        noCompress += listOf("onnx")
    }
    // Design references live in /ref_ui at the repo root and are never part of any source set
    // (assets come only from the default src/main/assets).
}

// AGP 9 compiles Kotlin itself (built-in Kotlin); only the compiler options are configured here.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.oboe)
    implementation(libs.onnxruntime.android)
    ortAar(libs.onnxruntime.android)

    // JVM unit tests (app/src/test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // On-device integration tests (app/src/androidTest)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------------------------
// ONNX Runtime C++ headers + .so are taken from the same AAR that is packaged into the APK, so
// the native code links against exactly the runtime version that ships.
// ---------------------------------------------------------------------------------------------
val extractOrt = tasks.register<Sync>("extractOrt") {
    from({ zipTree(ortAar.singleFile) }) { include("headers/**", "jni/**") }
    into(layout.buildDirectory.dir("ort"))
}
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(extractOrt) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------------------------
// APK hygiene checks (PRD §0 / Phase 5 audit). Run: ./gradlew :app:verifyApkHygieneDebug
// ---------------------------------------------------------------------------------------------
listOf("Debug", "Release").forEach { variant ->
    tasks.register("verifyApkHygiene$variant") {
        group = "verification"
        description = "Fails if ref_ui assets or network permissions leak into the $variant APK."
        dependsOn("assemble$variant")
        doLast {
            val apkDir = layout.buildDirectory.dir("outputs/apk/${variant.lowercase()}").get().asFile
            val apks = apkDir.walkTopDown().filter { it.extension == "apk" }.toList()
            check(apks.isNotEmpty()) { "No APK found in $apkDir" }
            apks.forEach { apk ->
                ZipFile(apk).use { zip ->
                    val leaked = zip.entries().asSequence().map { it.name }
                        .filter { it.contains("ref_ui", ignoreCase = true) }.toList()
                    check(leaked.isEmpty()) { "ref_ui content found in ${apk.name}: $leaked" }
                }
                println("OK: ${apk.name} contains no ref_ui entries")
            }
            val merged = layout.buildDirectory
                .file("intermediates/merged_manifests/${variant.replaceFirstChar { it.lowercase() }}/process${variant}Manifest/AndroidManifest.xml")
                .get().asFile
            val manifestFile = if (merged.exists()) merged else
                layout.buildDirectory.get().asFile.walkTopDown()
                    .firstOrNull { it.name == "AndroidManifest.xml" && it.path.contains("merged_manifest") && it.path.contains(variant.lowercase()) }
            check(manifestFile != null && manifestFile.exists()) { "Merged manifest not found" }
            val text = manifestFile.readText()
            listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE").forEach { perm ->
                val granted = Regex("<uses-permission[^>]*\"$perm\"[^>]*/>").findAll(text)
                    .any { !it.value.contains("tools:node=\"remove\"") }
                check(!granted) { "Forbidden permission $perm present in merged manifest" }
            }
            println("OK: merged manifest has no network permissions")
        }
    }
}
