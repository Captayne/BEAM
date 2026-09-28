# Dot-source this file to use the bundled build tools in PowerShell.
$env:JAVA_HOME = Join-Path $PSScriptRoot 'jdk'
$env:ANDROID_HOME = Join-Path $PSScriptRoot 'android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot 'gradle-home'
$env:PATH = "$env:JAVA_HOME\bin;$PSScriptRoot\wix3;$env:ANDROID_HOME\platform-tools;$env:PATH"
