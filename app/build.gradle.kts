import java.util.Properties
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// --- Auto-Versionierung ---------------------------------------------------
// Version steckt als ganze Zahl in app/version.properties. versionName = code/100
// (145 -> "1.45"). Bei jedem Build/Deploy (assemble/install) wird um 1 (= 0.01)
// hochgezählt; der neue Wert wird zurückgeschrieben. Reine Compile-/Test-Tasks
// zählen NICHT hoch, damit die Nummer nur bei echten Builds steigt.
val versionPropsFile = file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
val isBuildOrDeploy = gradle.startParameter.taskNames.any { name ->
    name.contains("assemble", ignoreCase = true) || name.contains("install", ignoreCase = true)
}
val resolvedVersionCode = run {
    var code = (versionProps.getProperty("versionCode") ?: "144").trim().toInt()
    if (isBuildOrDeploy) {
        code += 1
        versionProps.setProperty("versionCode", code.toString())
        versionPropsFile.outputStream().use { versionProps.store(it, "Auto-incremented on build/deploy") }
    }
    code
}
val resolvedVersionName = String.format(Locale.US, "%.2f", resolvedVersionCode / 100.0)

android {
    namespace = "de.systragon.beam"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.systragon.beam"
        minSdk = 24
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    // Verhindert Konflikte bei nativen .so-Bibliotheken von libtorrent4j
    packaging {
        jniLibs {
            pickFirsts += "**/*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Plattformfreier Kern (geteilt mit :beam-desktop)
    implementation(project(":beam-core"))
    // libtorrent4j — Torrent-Engine (Kern + Android-Architekturen)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-31")
    implementation("androidx.compose.material:material-icons-extended")
    // ExoPlayer (media3) — Video-Streaming während des Downloads
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // media3 Transformer — Video-Vorkomprimierung beim Senden
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}