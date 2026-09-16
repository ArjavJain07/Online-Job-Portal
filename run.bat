@echo off
rem run.bat - starts the Online Job Portal with Gradle. Usage: run.bat [port]
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install JDK 17 or newer and try again.
  pause
  exit /b 1
)
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"
echo Starting on http://localhost:%PORT% ...
call gradlew.bat bootRun --args="--server.port=%PORT%"
