---
name: build-setup
description: Wie das Beam-Android-Projekt von der Kommandozeile gebaut wird (JAVA_HOME-Pfad)
metadata: 
  node_type: memory
  type: reference
  originSessionId: e9685cb5-084b-4843-bbbc-fb9be3e903c6
---

`java` ist auf dieser Maschine NICHT im PATH und `JAVA_HOME` ist leer. Android Studio liegt an
einem nicht-standard Ort: `C:\Installed\Android\Android Studio` — das gebündelte JBR (JDK 21)
unter `C:\Installed\Android\Android Studio\jbr`.

Debug-Build von der Projektwurzel (PowerShell):
```
$env:JAVA_HOME = "C:\Installed\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug --console=plain
```

Android SDK: `C:\Users\KEIBEL-OFFICE\AppData\Local\Android\Sdk` (in `local.properties`).
libtorrent4j-JAR für API-Inspektion via `javap`:
`~\.gradle\caches\modules-2\files-2.1\org.libtorrent4j\libtorrent4j\2.1.0-31\...\libtorrent4j-2.1.0-31.jar`
