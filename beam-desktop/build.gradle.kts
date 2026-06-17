import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
}

// --- Auto-Versionierung (MSI-Upgrade) -------------------------------------
// packageVersion MUSS bei jedem Release steigen, sonst sieht Windows „gleiche Version" und
// verlangt manuelle Deinstallation statt zu updaten. Buildnummer steckt in version.properties;
// nur beim echten Verpacken (package*/createDistributable) wird hochgezählt → Version "1.0.<n>".
// (3. Stelle, weil Windows-MSI nur die ersten drei Felder zum Upgrade-Vergleich nutzt.)
val desktopVersionFile = file("version.properties")
val desktopVersionProps = Properties().apply {
    if (desktopVersionFile.exists()) desktopVersionFile.inputStream().use { load(it) }
}
val isPackaging = gradle.startParameter.taskNames.any { name ->
    name.contains("package", ignoreCase = true) || name.contains("createDistributable", ignoreCase = true)
}
val desktopBuildNumber = run {
    var n = (desktopVersionProps.getProperty("buildNumber") ?: "0").trim().toInt()
    if (isPackaging) {
        n += 1
        desktopVersionProps.setProperty("buildNumber", n.toString())
        desktopVersionFile.outputStream().use { desktopVersionProps.store(it, "Auto-incremented on packaging") }
    }
    n
}
val desktopPackageVersion = "1.0.$desktopBuildNumber"

// Help-Markdown zentral im App-assets-Ordner pflegen (eine Quelle für App + Desktop).
// Von dort werden die beamDesk_help_<lang>.md mit ins Desktop-Image gepackt (useResource).
sourceSets.named("main") {
    resources.srcDir(rootProject.file("app/src/main/assets"))
}

// BBR-Release (opt-in via -PbbrDll / BEAM_BBR_DLL): unsere gepatchte libtorrent4j (BBR als µTP-Default)
// INS PAKET bündeln statt der Stock-DLL. Die DLL liegt unter src/bbr/resources/lib/x86_64/libtorrent4j.dll
// und wird von ensureNativeLib() aus dem Classpath extrahiert — KEIN absoluter Pfad, läuft auf jeder
// Maschine (wichtig fürs Freunde-MSI!). Das passende -39-Jar wird forciert (JNI-Match: bdecode_node_bdecode
// hat zwischen -31 und -39 eine andere Signatur). Normale Builds bleiben unberührt auf Stock-2.1.0-31.
val bbr = (project.findProperty("bbrDll") as String?) != null || System.getenv("BEAM_BBR_DLL") != null
if (bbr) {
    configurations.all { resolutionStrategy.force("org.libtorrent4j:libtorrent4j:2.1.0-39-beam-bbr1") }
    sourceSets.named("main") { resources.srcDir("src/bbr/resources") }
}

dependencies {
    implementation(project(":beam-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // libtorrent4j-Desktop-Native-Libs (für echtes Seeding/Download auf dem PC, später genutzt).
    // currentOs greift zur Laufzeit; alle drei deklarieren macht die Distribution portabel.
    if (!bbr) runtimeOnly("org.libtorrent4j:libtorrent4j-windows:2.1.0-31") // BBR: DLL kommt aus src/bbr/resources
    runtimeOnly("org.libtorrent4j:libtorrent4j-linux:2.1.0-31")
    runtimeOnly("org.libtorrent4j:libtorrent4j-macos:2.1.0-31")
}

compose.desktop {
    application {
        mainClass = "de.systragon.beam.desktop.MainKt"
        // jpackage/jlink-JDK explizit: die JBR (Standard-Gradle-JVM) hat kein jpackage.
        // Per -PjpackageJdk=… oder JAVA_HOME (z. B. Temurin 21) setzen.
        (project.findProperty("jpackageJdk") as String? ?: System.getenv("JAVA_HOME"))?.let { javaHome = it }

        // Version zur Laufzeit verfügbar machen (für die Titelzeile) — gilt für `run` UND das Paket.
        jvmArgs += "-Dbeam.version=$desktopPackageVersion"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg)
            packageName = "Beam"
            packageVersion = desktopPackageVersion
            // Zusätzliche Dateien fest mit ins App-Image packen (z. B. das gebündelte ffmpeg.exe für
            // die Video-Komprimierung). Zur Laufzeit erreichbar über die System-Property
            // "compose.application.resources.dir".
            appResourcesRootDir.set(project.file("desktop-resources"))
            description = "Beam Desktop"
            vendor = "Systragon"

            // .beam-Dateien mit Beam verknüpfen → Doppelklick öffnet Beam und empfängt (main(args)).
            fileAssociation(
                mimeType = "application/x-beam",
                extension = "beam",
                description = "Beam transfer link"
            )

            windows {
                menuGroup = "Beam"
                // Stabile UpgradeUUID, damit künftige MSIs als Upgrade (nicht parallel) installieren.
                upgradeUuid = "8f4b2e7a-3c1d-4a55-9b2e-0a1b2c3d4e5f"
                shortcut = true
                // Gleiches Icon wie die Android-App (aus deren Launcher-Icon erzeugt).
                iconFile.set(project.file("icons/beam.ico"))
            }
        }
    }
}
