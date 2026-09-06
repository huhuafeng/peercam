plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 版本号自动推导：
 *  - versionName：取 git 最近 tag（如 v1.2.3 → "1.2.3"），本地无 git/tag 时回退 "1.0.0"
 *  - versionCode：tag 三段数字转数字（v1.2.3 → 10203），保证每次发版可覆盖安装
 */
fun resolveVersionName(): String {
    val tag = try {
        val p = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    } catch (e: Exception) {
        ""
    }
    return tag.removePrefix("v").ifBlank { "1.0.0" }
}

fun resolveVersionCode(): Int {
    val name = resolveVersionName()
    val parts = name.split(".").filter { it.isNotEmpty() }
    if (parts.size >= 3) {
        val a = parts[0].toIntOrNull() ?: 1
        val b = parts[1].toIntOrNull() ?: 0
        val c = parts[2].toIntOrNull() ?: 0
        return a * 10000 + b * 100 + c
    }
    return 100 // 回退
}

android {
    namespace = "com.peercam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peercam.app"
        minSdk = 26
        targetSdk = 35
        versionCode = resolveVersionCode()
        versionName = resolveVersionName()
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // androidx.exifinterface not needed; keep deps minimal
}
