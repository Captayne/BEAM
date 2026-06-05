# Erstellt die "Senden an -> BEAM!"-Verknuepfung im Windows-SendTo-Ordner.
# Voraussetzung: vorher die App-Distribution bauen:
#   $env:JAVA_HOME="<JDK21-mit-jpackage>"; .\gradlew.bat :beam-desktop:createDistributable
# Dann dieses Skript ausfuehren.

$ErrorActionPreference = "Stop"

$exe = Join-Path $PSScriptRoot "..\beam-desktop\build\compose\binaries\main\app\Beam\Beam.exe"
if (-not (Test-Path $exe)) {
    Write-Error "Beam.exe nicht gefunden unter: $exe`nErst bauen: gradlew :beam-desktop:createDistributable"
    exit 1
}
$exe = (Resolve-Path $exe).Path

$sendTo = Join-Path $env:APPDATA "Microsoft\Windows\SendTo"
$lnk = Join-Path $sendTo "BEAM!.lnk"

$ws = New-Object -ComObject WScript.Shell
$shortcut = $ws.CreateShortcut($lnk)
$shortcut.TargetPath = $exe
$shortcut.WorkingDirectory = (Split-Path $exe)
$shortcut.Description = "Datei(en) mit Beam teilen"
$shortcut.Save()

Write-Output "OK: 'Senden an -> BEAM!' angelegt."
Write-Output "  Verknuepfung: $lnk"
Write-Output "  Ziel:         $exe"
Write-Output "Jetzt: im Explorer Datei(en) markieren -> Rechtsklick -> Senden an -> BEAM!"
