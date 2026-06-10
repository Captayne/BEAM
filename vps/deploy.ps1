# vps\deploy.ps1 -- baut die Relay-Distribution und deployt sie aufs VPS.
# Aufruf aus der Repo-Wurzel ODER aus vps\:  .\vps\deploy.ps1
# Schiebt ALLE Runtime-Jars (lib\*) -> fuer ersten Aufbau UND Updates gleichermassen ok.
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"   # JDK 21
$vps = "root@217.160.159.14"
$key = "$env:USERPROFILE\.ssh\beam_relay"

$repo = Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    Write-Host "== 1/3  Baue Relay-Distribution (installDist) ==" -ForegroundColor Cyan
    .\gradlew.bat :beam-relay:installDist --console=plain
    if ($LASTEXITCODE -ne 0) { throw "installDist fehlgeschlagen" }

    $lib = Join-Path $repo "beam-relay\build\install\beam-relay\lib"
    if (-not (Test-Path $lib)) { throw "lib nicht gefunden: $lib" }
    Write-Host "== 2/3  Kopiere lib\* aufs VPS (/root/beam-relay/lib/) ==" -ForegroundColor Cyan
    ssh -i $key -o BatchMode=yes $vps "mkdir -p /root/beam-relay/lib"
    scp -i $key "$lib\*" "${vps}:/root/beam-relay/lib/"
    if ($LASTEXITCODE -ne 0) { throw "scp fehlgeschlagen" }

    Write-Host "== 3/3  Dienst neu starten ==" -ForegroundColor Cyan
    ssh -i $key -o BatchMode=yes $vps "systemctl restart beam-relay; sleep 3; systemctl is-active beam-relay; tail -4 /root/relay.log"
    Write-Host "== Fertig. ==" -ForegroundColor Green
}
finally { Pop-Location }
