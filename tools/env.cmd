@rem Prefer bundled tools when present; preserve a configured JDK/SDK on CI.
if exist "%~dp0jdk\bin\java.exe" set "JAVA_HOME=%~dp0jdk"
if exist "%~dp0android-sdk\platforms" set "ANDROID_HOME=%~dp0android-sdk"
if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if exist "%~dp0gradle-home" set "GRADLE_USER_HOME=%~dp0gradle-home"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
if exist "%~dp0wix3" set "PATH=%~dp0wix3;%PATH%"
if defined ANDROID_HOME set "PATH=%ANDROID_HOME%\platform-tools;%PATH%"
