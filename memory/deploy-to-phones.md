---
name: deploy-to-phones
description: "APK auf die Testgeräte (S23 + S26) aufspielen — dynamisch über adb devices, nicht hardcoden"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 944356e9-b04c-4da2-bf38-6fd02b79c83d
---

Zwei Testgeräte, beide per **WiFi-Debugging**:
- `R3CW4085DHV` = **S23 Ultra** (SM-S918B)
- `R3GL307R03L` = **S26 Ultra** (SM-S948B)

**Aufspielen auf alle Geräte, die gerade online sind** (genau diese zwei). Nicht `transport_id`
oder den vollen mDNS-`-s`-String hardcoden — die ändern sich bei Reconnect/Neu-Pairen. Stattdessen
dynamisch aus `adb devices` ziehen:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = "app\build\outputs\apk\debug\app-debug.apk"
& $adb devices | Select-String "device$" | ForEach-Object {
    $id = ($_ -split "\s+")[0]
    & $adb -s $id install -r $apk
}
```

adb liegt unter `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.

**Wenn eins fehlt:** oft ist nur die **adb-Verbindung** abgerissen, das Gerät aber noch im Netz.
Erst versuchen wiederzuverbinden, bevor man den User bemüht:
```powershell
& $adb mdns services        # zeigt Geräte, die noch advertisen, mit IP:Port
& $adb connect 192.168.x.y:PORT   # holt das Gerät ohne Aktion am Handy zurück
```
Klappt das nicht (Gerät gar nicht mehr im mDNS), DANN ist das WiFi-Debugging am Handy raus →
Sache des Users (wieder einschalten), nicht weiter debuggen. Einfach kurz melden und auf das
vorhandene Gerät aufspielen.

LAN-IPs im Office-WLAN (192.168.178.x): S23 = .41, S26 = .65 (können per DHCP wechseln).

**Stale „offline"-Transport:** Steht ein Gerät in `adb devices` als `offline` (alter ip:port, Port
hat gewechselt), erst `adb disconnect <alt-ip:port>`, dann frischen Port aus `adb mdns services`
holen und neu `adb connect`. Beim Filtern nur Zeilen nehmen, die auf `\sdevice$` enden (nicht
`offline`), sonst installiert man auf tote Transporte. Ein Gerät kann doppelt gelistet sein
(mDNS-Name + ip:port) — beide zeigen aufs selbe Gerät, doppeltes Install ist harmlos.

Bauen vorher via [[build-setup]]. Nach Claude-Edits ggf. [[studio-sync-after-edits]] beachten.
