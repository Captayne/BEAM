---
name: studio-sync-after-edits
description: "Android Studio baut nach Claude-Edits altes APK, wenn nicht von Disk neu geladen"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e9685cb5-084b-4843-bbbc-fb9be3e903c6
---

Der Nutzer baut/deployt über Android Studio. Wenn Claude Dateien direkt auf der Platte ändert,
sieht Studio die Änderungen wegen seines VFS-Caches nicht zwingend und baut die alte Version →
altes Verhalten auf dem Handy, obwohl der Code auf der Platte korrekt ist.

**Why:** Hat den Nutzer schon einmal verwirrt ("erzeugt kein neues executable").

**How to apply:** Nach Edits dem Nutzer sagen: in Studio **File → Reload All from Disk**, dann
**Clean Project → Rebuild → Run**. Schnellster Workaround: das per `gradlew assembleDebug` gebaute
APK direkt via ADB aufspielen — Gerät war verbunden:
`& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`.
Siehe [[build-setup]].
