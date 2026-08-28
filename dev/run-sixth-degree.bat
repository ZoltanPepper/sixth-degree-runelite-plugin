@echo off
setlocal
cd /d "%~dp0"

set "JAVA_CMD=%LOCALAPPDATA%\RuneLite\jre\bin\java.exe"
if exist "%JAVA_CMD%" goto java_ready

where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo Could not find RuneLite's bundled Java or a system Java runtime.
  echo Make sure the normal RuneLite Windows launcher is installed, then try again.
  echo Expected RuneLite Java path:
  echo %LOCALAPPDATA%\RuneLite\jre\bin\java.exe
  echo.
  pause
  exit /b 1
)
set "JAVA_CMD=java"

:java_ready
echo Starting RuneLite with the Sixth Degree development plugin...
echo Java: %JAVA_CMD%
echo.

"%JAVA_CMD%" -ea -cp "app\sixth-degree-runelite-dev.jar;app\lib\*" com.sixthdegree.SixthDegreePluginTest --developer-mode --debug
set EXIT_CODE=%ERRORLEVEL%

if not "%EXIT_CODE%"=="0" (
  echo.
  echo RuneLite exited with code %EXIT_CODE%.
  echo Keep this window open and copy any error text if you need help.
  pause
)

exit /b %EXIT_CODE%
