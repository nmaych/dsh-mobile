@echo off
REM ---------------------------------------------------------------------------
REM  Build DSH Mobile.
REM
REM  Two toolchain sources, tried in order:
REM
REM    1. .\toolchain\  - a self-contained JDK 17 + Android SDK 34 + Gradle 8.7
REM       this repo can bootstrap locally. Reproducible, and works offline once
REM       fetched.
REM    2. The machine's own JAVA_HOME / ANDROID_HOME, driven through the Gradle
REM       wrapper. This is what a fresh clone uses, and what CI uses.
REM
REM  Usage:
REM      build.cmd                    assembleRelease (default)
REM      build.cmd assembleDebug
REM      build.cmd clean
REM      build.cmd bootstrap          fetch .\toolchain\ only
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "ROOT=%~dp0"
set "TC=%ROOT%toolchain"
set "PROJ=%ROOT%app-project"

if "%~1"=="" (set "TASK=assembleRelease") else (set "TASK=%~1")

REM ---------------------------------------------------------------------------
REM  Resolve a JDK.
REM ---------------------------------------------------------------------------
set "BUNDLED_JDK="
for /d %%d in ("%TC%\jdk\*") do if exist "%%d\bin\java.exe" set "BUNDLED_JDK=%%d"

if defined BUNDLED_JDK (
  set "JAVA_HOME=%BUNDLED_JDK%"
) else if not defined JAVA_HOME (
  echo [ERROR] No JDK found.
  echo.
  echo   Either run "build.cmd bootstrap" to fetch a self-contained toolchain,
  echo   or install JDK 17 and set JAVA_HOME.
  exit /b 1
)

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JAVA_HOME does not point at a JDK: %JAVA_HOME%
  exit /b 1
)

REM ---------------------------------------------------------------------------
REM  Resolve an Android SDK.
REM ---------------------------------------------------------------------------
if exist "%TC%\sdk\platforms\android-34\android.jar" (
  set "ANDROID_HOME=%TC%\sdk"
) else if not defined ANDROID_HOME (
  echo [ERROR] No Android SDK found.
  echo.
  echo   Either run "build.cmd bootstrap" to fetch a self-contained toolchain,
  echo   or install the Android SDK ^(platform 34 + build-tools 34.0.0^)
  echo   and set ANDROID_HOME.
  exit /b 1
)
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

if not exist "%ANDROID_HOME%\platforms\android-34\android.jar" (
  echo [ERROR] Android SDK platform 34 is missing from %ANDROID_HOME%
  echo         Install it with: sdkmanager "platforms;android-34" "build-tools;34.0.0"
  exit /b 1
)

REM Keep Gradle's caches with the toolchain when we have one, so a bundled
REM build never touches the user's global ~/.gradle.
if defined BUNDLED_JDK if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%TC%\gradle-home"

REM The Gradle build reads local.properties for the SDK path. Regenerate it so a
REM stale path from another machine cannot break the build.
> "%PROJ%\local.properties" echo sdk.dir=%ANDROID_HOME:\=\\%

echo [info] JAVA_HOME   = %JAVA_HOME%
echo [info] ANDROID_HOME= %ANDROID_HOME%
if defined GRADLE_USER_HOME echo [info] GRADLE_USER = %GRADLE_USER_HOME%
echo [info] task        = %TASK%
echo.

REM ---------------------------------------------------------------------------
REM  bootstrap: fetch the self-contained toolchain, then stop.
REM ---------------------------------------------------------------------------
if /i "%TASK%"=="bootstrap" (
  echo [info] Bootstrapping the bundled toolchain...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%tools\bootstrap-toolchain.ps1"
  exit /b %ERRORLEVEL%
)

REM ---------------------------------------------------------------------------
REM  Run Gradle. Prefer the bundled distribution; otherwise use the wrapper.
REM ---------------------------------------------------------------------------
set "GRADLE_CMD="
for /d %%d in ("%TC%\gradle-*") do if exist "%%d\bin\gradle.bat" set "GRADLE_CMD=%%d\bin\gradle.bat"

if defined GRADLE_CMD (
  call "%GRADLE_CMD%" %TASK% -p "%PROJ%" --no-daemon --console=plain
) else (
  if not exist "%PROJ%\gradlew.bat" (
    echo [ERROR] Neither a bundled Gradle nor the Gradle wrapper is available.
    exit /b 1
  )
  pushd "%PROJ%"
  call gradlew.bat %TASK% --no-daemon --console=plain
  popd
)

set "CODE=%ERRORLEVEL%"
if "%CODE%"=="0" (
  echo.
  echo [ok] %TASK% succeeded.
  if exist "%PROJ%\app\build\outputs\apk\release\app-release.apk" (
    echo      APK: %PROJ%\app\build\outputs\apk\release\app-release.apk
  )
  if exist "%PROJ%\app\build\outputs\apk\debug\app-debug.apk" (
    echo      APK: %PROJ%\app\build\outputs\apk\debug\app-debug.apk
  )
) else (
  echo.
  echo [FAIL] %TASK% exited with code %CODE%
)
endlocal & exit /b %CODE%
