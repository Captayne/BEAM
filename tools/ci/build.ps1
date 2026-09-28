param([Parameter(Mandatory)][ValidateSet('android', 'pc', 'vps')][string]$Target)
$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'This entry point is for GitHub Actions. Use build.cmd locally.' }
Set-Location -LiteralPath $root

# Distinct versions per workflow run; retries intentionally retain the same version.
# Only the temporary checkout is modified, never the source repository.
$runNumber = [int]$env:GITHUB_RUN_NUMBER
if ($runNumber -lt 1) { throw 'Missing GitHub run number.' }
function Set-VersionBase([string]$relative, [string]$key, [int]$maximum) {
    $path = Join-Path $root $relative
    $text = [IO.File]::ReadAllText($path)
    $pattern = '(?m)^' + [regex]::Escape($key) + '=(\d+)\s*$'
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) { throw "Version property missing: $relative" }
    $base = [long]$match.Groups[1].Value + $runNumber - 1
    if ($base + 1 -gt $maximum) { throw 'Version exceeds packaging limit.' }
    [IO.File]::WriteAllText($path, [regex]::Replace($text, $pattern, "$key=$base`n"))
}
if ($Target -eq 'android') { Set-VersionBase 'app/version.properties' 'versionCode' 2100000000 }
if ($Target -eq 'pc') { Set-VersionBase 'beam-desktop/version.properties' 'buildNumber' 65535 }

$tasks = switch ($Target) {
    'android' { @(':app:assembleDebug') }
    'pc' { @(':beam-desktop:packageMsi') }
    'vps' { @(':beam-relay:installDist', ':beam-relay:distZip') }
}
$gradleArgs = @($tasks) + @('--no-daemon', '--console=plain', '--stacktrace', '--max-workers=2')
if ($IsWindows) { & "$root/gradlew.bat" @gradleArgs }
else { & bash "$root/gradlew" @gradleArgs }
if ($LASTEXITCODE -ne 0) { throw "Gradle $Target build failed." }

$output = Join-Path $root "cloud-artifacts/$Target"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$artifact = switch ($Target) {
    'android' { Get-Item -LiteralPath "$root/app/build/outputs/apk/debug/app-debug.apk" }
    'pc' { Get-ChildItem -Path "$root/beam-desktop/build/compose/binaries/main/msi/Beam-*.msi" | Sort-Object LastWriteTime -Descending | Select-Object -First 1 }
    'vps' { Get-Item -LiteralPath "$root/beam-relay/build/distributions/beam-relay.zip" }
}
if (-not $artifact -or $artifact.Length -eq 0) { throw 'Expected package was not created.' }
Copy-Item -LiteralPath $artifact.FullName -Destination $output
Copy-Item -LiteralPath "$root/THIRD-PARTY-NOTICES.md" -Destination $output
$hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS.txt'), "$hash  $($artifact.Name)`n")
$info = [ordered]@{
    target = $Target
    commit = $env:GITHUB_SHA
    run = $runNumber
    attempt = $env:GITHUB_RUN_ATTEMPT
    runUrl = "$env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID"
    runner = $env:RUNNER_OS
    javaHome = $env:JAVA_HOME
    package = $artifact.Name
    bytes = $artifact.Length
    sha256 = $hash
    variant = 'standard libtorrent4j; no local BBR binaries'
}
$info | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'build-info.json') -Encoding utf8
if ($Target -eq 'android') {
    [IO.File]::WriteAllText((Join-Path $output 'ANDROID-TEST-BUILD.txt'), "Debug APK, signed with the temporary runner's debug key.`nThis key can differ between runs and from your local APK.`nAn update over an existing installation may be refused by Android.`nDo not uninstall an existing app without backing up its data.`nStable release signing has not been configured.`n")
}
@"
### BEAM $Target

- Package: $($artifact.Name)
- Size: $([math]::Round($artifact.Length / 1MB, 1)) MiB
- SHA256: $hash
- Download: artifact attached to this workflow run (retained for 7 days).
- Private test build; no installation or VPS deployment performed.
"@ | Out-File -FilePath $env:GITHUB_STEP_SUMMARY -Encoding utf8 -Append
