param([Parameter(Mandatory)][ValidateSet('android', 'pc', 'vps')][string]$Target)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'Run this setup on a GitHub-hosted runner.' }
Set-Location -LiteralPath $root

if (-not $env:JAVA_HOME) { throw 'Set up JDK 21 before preparing the build.' }
& (Join-Path $env:JAVA_HOME 'bin/java') -version
if ($LASTEXITCODE -ne 0) { throw 'JDK is not usable.' }

# All modules are configured together, so keep the SDK available for every target.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk -or -not (Test-Path -LiteralPath $sdk)) { throw 'The hosted runner Android SDK is missing.' }
$sdkManagerName = if ($IsWindows) { 'sdkmanager.bat' } else { 'sdkmanager' }
$sdkManager = Join-Path $sdk "cmdline-tools/latest/bin/$sdkManagerName"
if (-not (Test-Path -LiteralPath $sdkManager)) { throw "SDK manager not found: $sdkManager" }
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$sdk" 'platforms;android-35' 'build-tools;36.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Required Android SDK packages could not be installed.' }
$sdkPath = $sdk.Replace('\', '/')
[IO.File]::WriteAllText((Join-Path $root 'local.properties'), "sdk.dir=$sdkPath`n")
"ANDROID_HOME=$sdk" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append
"ANDROID_SDK_ROOT=$sdk" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append

if ($Target -eq 'pc') {
    if (-not $IsWindows) { throw 'MSI packaging requires the Windows runner.' }
    $version = '8.1.1'
    $expectedHash = '6f58ce889f59c311410f7d2b18895b33c03456463486f3b1ebc93d97a0f54541'
    $downloadDir = Join-Path $root 'tools/ffmpeg-download'
    New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null
    $zip = Join-Path $downloadDir "ffmpeg-$version-essentials_build.zip"
    $url = "https://github.com/GyanD/codexffmpeg/releases/download/$version/ffmpeg-$version-essentials_build.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip
    if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'FFmpeg archive SHA256 does not match the pinned release.'
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $downloadDir -Force
    $ffmpegRoot = Join-Path $downloadDir "ffmpeg-$version-essentials_build"
    $resources = Join-Path $root 'beam-desktop/desktop-resources/windows'
    $ffmpegDestination = Join-Path $resources 'ffmpeg'
    New-Item -ItemType Directory -Path $ffmpegDestination -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $ffmpegRoot 'bin/ffmpeg.exe') -Destination $ffmpegDestination
    $notices = @(Get-ChildItem -LiteralPath $ffmpegRoot -File | Where-Object { $_.Name -match '^(LICENSE|COPYING|README)' })
    if (-not ($notices | Where-Object { $_.Name -match '^(LICENSE|COPYING)' })) { throw 'FFmpeg license file missing.' }
    $notices | Copy-Item -Destination $ffmpegDestination
    Copy-Item -LiteralPath (Join-Path $root 'THIRD-PARTY-NOTICES.md') -Destination $resources
    [IO.File]::WriteAllText((Join-Path $ffmpegDestination 'SOURCE.txt'), "Version: $version`nArchive: $url`nSHA256: $expectedHash`nProvider: https://www.gyan.dev/ffmpeg/builds/`nPrivate test build; see THIRD-PARTY-NOTICES.md before public redistribution.`n")
    & (Join-Path $ffmpegDestination 'ffmpeg.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'Downloaded FFmpeg cannot start.' }
    # Compose's Gradle plugin downloads its matching WiX 3 toolset itself.
}
