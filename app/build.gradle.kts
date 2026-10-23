import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.cueopen.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.cueopen.app"
        minSdk = 26
        // targetSdk 28 (igual ao build F-Droid do Acode) permite executar binários
        // do sandbox a partir de filesDir. Os .so do proot também vêm em jniLibs.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } } // extrai .so para nativeLibraryDir
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Baixa o xterm.js (frontend do terminal, mesmo do Acode) para assets/terminal.
val xtermFiles = mapOf(
    "xterm.js" to "@xterm/xterm@5.5.0/lib/xterm.js",
    "xterm.css" to "@xterm/xterm@5.5.0/css/xterm.css",
    "addon-fit.js" to "@xterm/addon-fit@0.10.0/lib/addon-fit.js",
    "addon-web-links.js" to "@xterm/addon-web-links@0.11.0/lib/addon-web-links.js",
)
val downloadXterm by tasks.registering {
    val dir = file("src/main/assets/terminal")
    outputs.files(xtermFiles.keys.map { File(dir, it) })
    doLast {
        xtermFiles.forEach { (name, path) ->
            val out = File(dir, name)
            if (!out.exists() || out.length() == 0L) {
                logger.lifecycle("Baixando $name")
                URI("https://cdn.jsdelivr.net/npm/$path").toURL().openStream().use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadXterm) }
