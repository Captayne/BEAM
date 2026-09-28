@rem Local build tools; called by gradlew.bat after setlocal.
set "JAVA_HOME=%~dp0jdk"
set "ANDROID_HOME=%~dp0android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%~dp0gradle-home"
set "PATH=%JAVA_HOME%\bin;%~dp0wix3;%ANDROID_HOME%\platform-tools;%PATH%"
