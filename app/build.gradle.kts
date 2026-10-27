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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 21
        versionName = "4.8.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        // Chave de assinatura estável opcional: lida de variáveis de ambiente no CI (sem credenciais
        // commitadas). Quando presentes, garante assinatura fixa para atualizar o APK sem desinstalar.
        create("cueopen") {
            val ks = rootProject.file("app/cueopen-release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("CUEOPEN_STORE_PASSWORD") ?: "cueopen2026"
                keyAlias = System.getenv("CUEOPEN_KEY_ALIAS") ?: "cueopen"
                keyPassword = System.getenv("CUEOPEN_KEY_PASSWORD") ?: "cueopen2026"
            }
        }
    }
    buildTypes {
        release {
            // build otimizado: R8 (minify + otimização) deixa o Compose bem mais fluido que o debug
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Chave fixa do app (assinatura estável para atualizar sem desinstalar); cai para debug se faltar.
            signingConfig = if (rootProject.file("app/cueopen-release.jks").exists())
                signingConfigs.getByName("cueopen") else signingConfigs.getByName("debug")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } } // extrai .so para nativeLibraryDir
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
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
    implementation(project(":terminal-view"))
    implementation("com.caverock:androidsvg-aar:1.4")
    // Carrega ícones de tokens (logos remotos) na lista de ativos
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Markdown estilo GitHub no chat (tabelas, listas, citações) + highlight de código
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.27.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Solana keypair/signing primitives and BIP-39 mnemonic generation/validation.
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.sol4k:sol4k:0.8.2")
    implementation("cash.z.ecc.android:kotlin-bip39:1.0.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Assets web baixados na build (CDN jsDelivr): xterm.js (terminal legado) e o kit de Markdown
// estilo GitHub (github-markdown-css + marked + highlight.js + mermaid) para previews e diagramas.
val webAssets = mapOf(
    "terminal/xterm.js" to "@xterm/xterm@5.5.0/lib/xterm.js",
    "terminal/xterm.css" to "@xterm/xterm@5.5.0/css/xterm.css",
    "terminal/addon-fit.js" to "@xterm/addon-fit@0.10.0/lib/addon-fit.js",
    "terminal/addon-web-links.js" to "@xterm/addon-web-links@0.11.0/lib/addon-web-links.js",
    "md/github-markdown.css" to "github-markdown-css@5.5.1/github-markdown.css",
    "md/marked.min.js" to "marked@12.0.2/marked.min.js",
    "md/highlight.min.js" to "@highlightjs/cdn-assets@11.9.0/highlight.min.js",
    "md/hl-github.css" to "@highlightjs/cdn-assets@11.9.0/styles/github.min.css",
    "md/hl-github-dark.css" to "@highlightjs/cdn-assets@11.9.0/styles/github-dark.min.css",
    "md/mermaid.min.js" to "mermaid@10.9.1/dist/mermaid.min.js",
)
val downloadWebAssets by tasks.registering {
    val dir = file("src/main/assets")
    outputs.files(webAssets.keys.map { File(dir, it) })
    doLast {
        webAssets.forEach { (name, path) ->
            val out = File(dir, name).apply { parentFile.mkdirs() }
            if (!out.exists() || out.length() == 0L) {
                logger.lifecycle("Baixando $name")
                URI("https://cdn.jsdelivr.net/npm/$path").toURL().openStream().use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadWebAssets) }
