@echo off
rem ============================================
rem  Build MultiOpener APK
rem  Command-line build, no Android Studio needed
rem ============================================
set JAVA_HOME=D:\biancheng\java\jdk21
set ANDROID_HOME=D:\Android-sdk\Sdk
set GRADLE_BIN=D:\GradleCache\wrapper\dists\gradle-8.7-bin\af3un6e4ivqgjcdo5lfa5efog\gradle-8.7\bin\gradle.bat

echo Building release APK ...
"%GRADLE_BIN%" -p "%~dp0Shelter-gh" assembleRelease --no-daemon --console=plain -x lintVitalRelease

if %ERRORLEVEL%==0 (
    echo.
    echo BUILD OK: %~dp0Shelter-gh\app\build\outputs\apk\release\app-release.apk
) else (
    echo.
    echo BUILD FAILED, see messages above.
)
pause
