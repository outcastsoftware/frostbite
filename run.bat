@echo off
rem Frostbite Desktop launcher — double-click to build (first run) and open the app.
setlocal
cd /d "%~dp0"
echo Starting Frostbite Desktop...
call gradlew.bat run
if errorlevel 1 (
  echo.
  echo Frostbite Desktop failed to start. Make sure a JDK is installed and ANDROID_HOME points to your Android SDK.
  pause
)
endlocal
