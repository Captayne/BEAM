@echo off
setlocal
cd /d "%~dp0"
set "BEAM_TARGET=%~1"
if "%BEAM_TARGET%"=="" set "BEAM_TARGET=all"
if /i "%BEAM_TARGET%"=="all" goto all
if /i "%BEAM_TARGET%"=="android" goto android
if /i "%BEAM_TARGET%"=="pc" goto pc
if /i "%BEAM_TARGET%"=="vps" goto vps
echo Usage: build.cmd [all^|android^|pc^|vps]
exit /b 2
:all
call gradlew.bat :app:assembleDebug :beam-desktop:packageMsi :beam-relay:installDist :beam-relay:distZip --offline --console=plain
exit /b %ERRORLEVEL%
:android
call gradlew.bat :app:assembleDebug --offline --console=plain
exit /b %ERRORLEVEL%
:pc
call gradlew.bat :beam-desktop:packageMsi --offline --console=plain
exit /b %ERRORLEVEL%
:vps
call gradlew.bat :beam-relay:installDist :beam-relay:distZip --offline --console=plain
exit /b %ERRORLEVEL%
