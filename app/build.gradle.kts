import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appMinSdk = 23
val maxVersionCode = 2_100_000_000
val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.let { raw ->
    require(raw.all(Char::isDigit)) { "GITHUB_RUN_NUMBER must be numeric" }
    raw.toInt().also { require(it <= maxVersionCode - 10_000) }
} ?: 0
val sourceSha = providers.environmentVariable("GITHUB_SHA").getOrElse("local")
val buildTimeUtc = providers.environmentVariable("CAMERA_BUILD_TIME_UTC").getOrElse("unknown")
val devSignerSha256 = "194bea7868e8a5d4a20d0dc22474e15f3f617eeb90471d2cd7ea405d341774e1"
val devUpdateMetadataUrl = "https://github.com/sahid-code404/ex3/releases/download/dev-latest/update.json"

val encodedDevSigner = rootProject.file("tools/dev-signing/camera-dev.jks.b64")
val decodedDevSigner = layout.buildDirectory.file("dev-signing/camera-dev.jks").get().asFile
check(encodedDevSigner.isFile) { "Permanent Development signer is missing" }
val signerBytes = Base64.getMimeDecoder().decode(encodedDevSigner.readText())
decodedDevSigner.parentFile.mkdirs()
if (!decodedDevSigner.isFile || !decodedDevSigner.readBytes().contentEquals(signerBytes)) {
    decodedDevSigner.writeBytes(signerBytes)
}

fun String.asBuildConfigLiteral(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    // Internal Kotlin namespace stays stable; the installed Android package identity is applicationId below.
    namespace = "com.sahidcode404.camera"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.sahidcode404.universalcamera"
        minSdk = appMinSdk
        targetSdk = 37
        versionCode = 10_000 + ciRunNumber
        versionName = if (ciRunNumber == 0) "0.1.0-foundation" else "0.1.$ciRunNumber-dev"
        buildConfigField("int", "BASELINE_MIN_SDK", appMinSdk.toString())
        buildConfigField("String", "GIT_SHA", sourceSha.asBuildConfigLiteral())
        buildConfigField("String", "BUILD_TIME_UTC", buildTimeUtc.asBuildConfigLiteral())
        buildConfigField("String", "DEV_SIGNER_SHA256", devSignerSha256.asBuildConfigLiteral())
        buildConfigField("String", "DEV_UPDATE_METADATA_URL", devUpdateMetadataUrl.asBuildConfigLiteral())
        buildConfigField("String", "OTA_CHANNEL", "none".asBuildConfigLiteral())

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra", "-Werror", "-fvisibility=hidden")
            }
        }
    }

    signingConfigs {
        create("devOta") {
            storeFile = decodedDevSigner
            storePassword = "camera-dev-only-2026"
            keyAlias = "camera-dev"
            keyPassword = "camera-dev-only-2026"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug")
        create("devOta") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("devOta")
            isDebuggable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "OTA_CHANNEL", "development".asBuildConfigLiteral())
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("../native/core/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        checkReleaseBuilds = true
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        check(variant.minSdk == appMinSdk) {
            "Camera must remain minSdk $appMinSdk; ${variant.name} resolved ${variant.minSdk}"
        }
    }
}

tasks.register("verifyApi23Baseline") {
    group = "verification"
    doLast {
        check(android.defaultConfig.minSdk == 23) { "Camera minSdk changed from API 23" }
        println("Camera Android baseline verified: minSdk=23")
    }
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
