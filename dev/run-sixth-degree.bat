@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo Java 11 or newer is required to run this development build.
  echo Install a Java 11+ runtime, then run this file again.
  echo.
  pause
  exit /b 1
)

echo Starting RuneLite with the Sixth Degree development plugin...
echo This uses the normal RuneLite client in developer mode.
echo.

java -ea -cp "app\sixth-degree-runelite-dev.jar;app\lib\*" com.sixthdegree.SixthDegreePluginTest --developer-mode --debug
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
  echo.
  echo RuneLite exited with code %EXIT_CODE%.
  echo Keep this window open and copy any error text if you need help.
  pause
)

exit /b %EXIT_CODE%
