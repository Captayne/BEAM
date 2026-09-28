# build-pc-beam.ps1
# Baut die PC-Beam-MSI und laedt sie token-gated aufs VPS (/root/beam-dist/Beam.msi),
# damit "Share PC-Beam!" auf Android immer die NEUESTE PC-Version verteilt.
# Aufruf:  .\build-pc-beam.ps1
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\tools\env.ps1"
$key = "$env:USERPROFILE\.ssh\beam_relay"
$vps = "root@217.160.159.14"

Push-Location $PSScriptRoot
try {
    Write-Host "== 1/3  Baue PC-Beam MSI ==" -ForegroundColor Cyan
    .\gradlew.bat :beam-desktop:packageMsi --console=plain
    if ($LASTEXITCODE -ne 0) { throw "MSI-Build fehlgeschlagen" }

    $msi = Get-ChildItem "beam-desktop\build\compose\binaries\main\msi\Beam-*.msi" |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $msi) { throw "Keine MSI gefunden" }
    Write-Host ("== 2/3  Gebaut: {0} ({1} MB) ==" -f $msi.Name, [math]::Round($msi.Length/1MB,1)) -ForegroundColor Cyan

    Write-Host "== 3/3  Upload aufs VPS (Beam.msi) ==" -ForegroundColor Cyan
    scp -i $key -o BatchMode=yes $msi.FullName "${vps}:/root/beam-dist/Beam.msi"
    if ($LASTEXITCODE -ne 0) { throw "Upload fehlgeschlagen" }

    # Verifikation: token-gated HEAD
    $size = ssh -i $key -o BatchMode=yes -n $vps "stat -c%s /root/beam-dist/Beam.msi"
    Write-Host ("== Fertig! Neueste PC-Version liegt token-gated auf dem VPS ({0} MB). ==" -f [math]::Round([int64]$size/1MB,1)) -ForegroundColor Green
    Write-Host "   Download-URL (nur mit Token): http://217.160.159.14/<token>/Beam.msi"
}
finally { Pop-Location }
