@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

rem ---------------------------------------------------------------------------
rem OwnerGuard automatic Android SDK bootstrap for Windows build agents.
rem Installs only the SDK components required by compileSdk 33.
rem ---------------------------------------------------------------------------

if defined ANDROID_HOME (
  set "SDK_ROOT=%ANDROID_HOME%"
) else if defined ANDROID_SDK_ROOT (
  set "SDK_ROOT=%ANDROID_SDK_ROOT%"
) else if defined LOCALAPPDATA (
  set "SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
) else (
  set "SDK_ROOT=%USERPROFILE%\AppData\Local\Android\Sdk"
)

set "ANDROID_HOME=%SDK_ROOT%"
set "ANDROID_SDK_ROOT=%SDK_ROOT%"
set "SDKMANAGER=%SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat"
set "ANDROID_JAR=%SDK_ROOT%\platforms\android-33\android.jar"
set "AAPT2=%SDK_ROOT%\build-tools\33.0.2\aapt2.exe"

if not exist "%ANDROID_JAR%" goto :bootstrap_sdk
if not exist "%AAPT2%" goto :bootstrap_sdk
goto :write_local_properties

:bootstrap_sdk
echo.
echo [OwnerGuard] Android SDK 33 is missing. Bootstrapping it now...
where curl.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: curl.exe is required to download Android command-line tools.
  exit /b 1
)
where tar.exe >nul 2>&1
if errorlevel 1 (
  echo ERROR: tar.exe is required to extract Android command-line tools.
  exit /b 1
)

set "SDK_ZIP=%TEMP%\ownerguard_commandlinetools_win.zip"
set "SDK_EXTRACT=%TEMP%\ownerguard_commandlinetools_extract"

if not exist "%SDKMANAGER%" (
  if exist "%SDK_EXTRACT%" rmdir /s /q "%SDK_EXTRACT%"
  mkdir "%SDK_EXTRACT%" >nul 2>&1
  if not exist "%SDK_ROOT%\cmdline-tools\latest" mkdir "%SDK_ROOT%\cmdline-tools\latest" >nul 2>&1

  echo [OwnerGuard] Downloading official Android command-line tools...
  curl.exe -L --fail --retry 4 --connect-timeout 30 ^
    -o "%SDK_ZIP%" ^
    "https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip"
  if errorlevel 1 (
    echo ERROR: Android command-line tools download failed.
    exit /b 1
  )

  echo [OwnerGuard] Extracting Android command-line tools...
  tar.exe -xf "%SDK_ZIP%" -C "%SDK_EXTRACT%"
  if errorlevel 1 (
    echo ERROR: Android command-line tools extraction failed.
    exit /b 1
  )

  xcopy /E /I /Y "%SDK_EXTRACT%\cmdline-tools\*" "%SDK_ROOT%\cmdline-tools\latest\" >nul
  if errorlevel 1 (
    echo ERROR: Android command-line tools installation failed.
    exit /b 1
  )
)

if not exist "%SDKMANAGER%" (
  echo ERROR: sdkmanager.bat was not found after installation.
  exit /b 1
)

echo [OwnerGuard] Accepting Android SDK licenses...
(for /L %%I in (1,1,30) do @echo y) | call "%SDKMANAGER%" --sdk_root="%SDK_ROOT%" --licenses >nul

echo [OwnerGuard] Installing platform 33 and build-tools 33.0.2...
call "%SDKMANAGER%" --sdk_root="%SDK_ROOT%" ^
  "platform-tools" ^
  "platforms;android-33" ^
  "build-tools;33.0.2"
if errorlevel 1 (
  echo ERROR: Required Android SDK packages could not be installed.
  exit /b 1
)

if not exist "%ANDROID_JAR%" (
  echo ERROR: Android platform 33 was not installed correctly.
  exit /b 1
)
if not exist "%AAPT2%" (
  echo ERROR: Android build-tools 33.0.2 were not installed correctly.
  exit /b 1
)

:write_local_properties
set "SDK_PROP=%SDK_ROOT:\=/%"
> "%APP_HOME%local.properties" echo sdk.dir=%SDK_PROP%

if not exist "%WRAPPER_JAR%" (
  echo [OwnerGuard] Gradle wrapper JAR missing. Downloading Gradle 8.2.1 wrapper...
  if not exist "%APP_HOME%gradle\wrapper" mkdir "%APP_HOME%gradle\wrapper"
  curl.exe -L --fail --retry 4 --connect-timeout 30 ^
    -o "%WRAPPER_JAR%.tmp" ^
    "https://github.com/gradle/gradle/raw/refs/tags/v8.2.1/gradle/wrapper/gradle-wrapper.jar"
  if errorlevel 1 (
    del /q "%WRAPPER_JAR%.tmp" >nul 2>&1
    echo ERROR: Could not download gradle-wrapper.jar.
    exit /b 1
  )
  move /y "%WRAPPER_JAR%.tmp" "%WRAPPER_JAR%" >nul
)

echo [OwnerGuard] SDK ready at: %SDK_ROOT%
java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
