# Prefer bundled tools; preserve a configured JDK/SDK on CI or a fresh clone.
if (Test-Path -LiteralPath "$PSScriptRoot\jdk\bin\java.exe") {
    $env:JAVA_HOME = Join-Path $PSScriptRoot 'jdk'
}
if (Test-Path -LiteralPath "$PSScriptRoot\android-sdk\platforms") {
    $env:ANDROID_HOME = Join-Path $PSScriptRoot 'android-sdk'
}
if ($env:ANDROID_HOME) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if (Test-Path -LiteralPath "$PSScriptRoot\gradle-home") {
    $env:GRADLE_USER_HOME = Join-Path $PSScriptRoot 'gradle-home'
}
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
if (Test-Path -LiteralPath "$PSScriptRoot\wix3") { $env:PATH = "$PSScriptRoot\wix3;$env:PATH" }
if ($env:ANDROID_HOME) { $env:PATH = "$env:ANDROID_HOME\platform-tools;$env:PATH" }
