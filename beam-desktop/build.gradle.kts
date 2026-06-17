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

// BBR-Test: wenn die gepatchte Nativ via -PbbrDll/BEAM_BBR_DLL geladen wird, muss auch das
// passende Java-Jar (gleicher -39-Stand wie die Nativ) her — sonst UnsatisfiedLinkError (z. B.
// bdecode_node_bdecode hat zwischen -31 und -39 eine andere Signatur). Nur dann forcieren;
// normale Builds bleiben auf dem Stock-2.1.0-31.
if ((project.findProperty("bbrDll") as String?) != null || System.getenv("BEAM_BBR_DLL") != null) {
    configurations.all {
        resolutionStrategy.force("org.libtorrent4j:libtorrent4j:2.1.0-39-beam-bbr1")
    }
}

dependencies {
    implementation(project(":beam-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // libtorrent4j-Desktop-Native-Libs (für echtes Seeding/Download auf dem PC, später genutzt).
    // currentOs greift zur Laufzeit; alle drei deklarieren macht die Distribution portabel.
    runtimeOnly("org.libtorrent4j:libtorrent4j-windows:2.1.0-31")
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

        // BBR-Test (opt-in): unsere gepatchte libtorrent4j-Nativ (mit BBR als µTP-Default) laden,
        // statt der gebundelten Stock-DLL. Nur aktiv, wenn der Pfad gesetzt ist → normale Builds bleiben
        // unberührt. Nutzung:  gradlew :beam-desktop:run -PbbrDll=C:\l4j\libtorrent4j\build\torrent4j.dll
        ((project.findProperty("bbrDll") as String?) ?: System.getenv("BEAM_BBR_DLL"))?.let { dll ->
            jvmArgs += "-Dlibtorrent4j.jni.path=$dll"
            println("[BBR] beam-desktop lädt Nativ-Lib: $dll")
        }

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
