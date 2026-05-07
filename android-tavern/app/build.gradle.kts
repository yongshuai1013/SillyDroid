import org.gradle.api.GradleException

plugins {
    id("com.android.application")
}

fun requireReleaseSigningValue(name: String, value: String): String {
    if (value.isBlank()) {
        throw GradleException("缺少 Android release 签名环境变量：$name")
    }

    return value
}

val requestedTasks = gradle.startParameter.taskNames.map { it.lowercase() }
val requiresReleaseSigning = requestedTasks.any { taskName ->
    taskName.contains("release") || taskName.contains("bundle")
}
val releaseSigningKeystorePath = System.getenv("STAI_ANDROID_RELEASE_KEYSTORE_PATH").orEmpty().trim()
val releaseSigningKeystorePassword = System.getenv("STAI_ANDROID_RELEASE_KEYSTORE_PASSWORD").orEmpty().trim()
val releaseSigningKeyAlias = System.getenv("STAI_ANDROID_RELEASE_KEY_ALIAS").orEmpty().trim()
val releaseSigningKeyPassword = System.getenv("STAI_ANDROID_RELEASE_KEY_PASSWORD").orEmpty().trim()

fun resolveAndroidVersionCode(rawValue: String): Int {
    if (rawValue.isBlank()) {
        return 1
    }

    return rawValue.toIntOrNull()?.takeIf { it > 0 }
        ?: throw GradleException("STAI_ANDROID_VERSION_CODE 必须是正整数：$rawValue")
}

val androidVersionCode = resolveAndroidVersionCode(System.getenv("STAI_ANDROID_VERSION_CODE").orEmpty().trim())
val androidVersionName = System.getenv("STAI_ANDROID_VERSION_NAME").orEmpty().trim().ifBlank { "0.1.0" }

android {
    namespace = "com.stai.sillytavern"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (requiresReleaseSigning) {
                val signingKeystoreFile = file(
                    requireReleaseSigningValue(
                        name = "STAI_ANDROID_RELEASE_KEYSTORE_PATH",
                        value = releaseSigningKeystorePath
                    )
                )

                if (!signingKeystoreFile.isFile) {
                    throw GradleException("Android release keystore 不存在：${signingKeystoreFile.absolutePath}")
                }

                storeFile = signingKeystoreFile
                storePassword = requireReleaseSigningValue(
                    name = "STAI_ANDROID_RELEASE_KEYSTORE_PASSWORD",
                    value = releaseSigningKeystorePassword
                )
                keyAlias = requireReleaseSigningValue(
                    name = "STAI_ANDROID_RELEASE_KEY_ALIAS",
                    value = releaseSigningKeyAlias
                )
                keyPassword = requireReleaseSigningValue(
                    name = "STAI_ANDROID_RELEASE_KEY_PASSWORD",
                    value = releaseSigningKeyPassword
                )
            }
        }
    }

    defaultConfig {
        applicationId = "com.stai.sillytavern"
        minSdk = 29
        targetSdk = 36
        versionCode = androidVersionCode
        versionName = androidVersionName
    }

    buildTypes {
        release {
            if (requiresReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
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

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("zip")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}