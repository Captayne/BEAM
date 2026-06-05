---
name: android-studio-jbr-path
description: Where the JDK/JBR lives for building this Android project from the CLI
metadata:
  type: reference
---

Android Studio ist auf diesem Rechner unter `C:\Installed\Android\Android Studio` installiert (nicht am Standardpfad). Es gibt sonst kein JDK auf PATH und `JAVA_HOME` ist nicht gesetzt.

Für CLI-Gradle-Builds:
```powershell
$env:JAVA_HOME="C:\Installed\Android\Android Studio\jbr"; $env:Path="$env:JAVA_HOME\bin;"+$env:Path
& .\gradlew.bat :app:compileDebugKotlin --console=plain
```
JBR = OpenJDK 21. Schneller Compile-Check des Beam!-Projekts: `:app:compileDebugKotlin`.
