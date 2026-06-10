plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    alias(libs.plugins.dokka)
}

// Plattformfreier Kern (Engine-Wrapper, Crypto, Peer-Hint, Tracker, .beam-Parsing).
// Wird von :app (Android) UND :beam-desktop genutzt → bewusst android-frei.
// Java-11-Bytecode, damit die Android-App (compileOptions VERSION_11) es konsumieren kann;
// kompiliert mit der laufenden JDK (kein Toolchain-Download).
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // libtorrent4j-Kern-API (plattformunabhängig). Die nativen .so/.dll-Libs kommen je Plattform
    // aus :app (Android-ABIs) bzw. :beam-desktop (windows/linux/macos).
    api("org.libtorrent4j:libtorrent4j:2.1.0-31")
}
