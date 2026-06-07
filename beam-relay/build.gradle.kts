plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Headless Relay-Daemon für die IONOS-VPS: nutzt den geteilten beam-core (TorrentManager, BeamLink,
// Trackers) + die NATIVEN libtorrent-Libs (Linux fürs Ziel, Windows fürs lokale Testen). Bewusst
// android-frei und ohne Compose. Java-11-Bytecode → läuft mit jedem JRE >= 11 auf dem Server.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

application {
    mainClass.set("de.systragon.beam.relay.RelayMainKt")
}

dependencies {
    implementation(project(":beam-core"))                          // TorrentManager, BeamLink, Trackers
    runtimeOnly("org.libtorrent4j:libtorrent4j-linux:2.1.0-31")    // Native .so fürs IONOS-Ziel
    runtimeOnly("org.libtorrent4j:libtorrent4j-windows:2.1.0-31")  // optional: lokal auf Windows testen
}
