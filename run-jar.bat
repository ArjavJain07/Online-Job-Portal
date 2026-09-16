@echo off
rem run-jar.bat - starts the packaged jar with Java only (no Gradle, works offline). Usage: run-jar.bat [port]
cd /d "%~dp0"
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"
set "JAR=build\libs\online-job-portal-1.0.0.jar"
if not exist "%JAR%" set "JAR=online-job-portal-1.0.0.jar"
if not exist "%JAR%" (
  echo Jar not found. Run "gradlew.bat bootJar" first, or put the jar next to this script.
  pause
  exit /b 1
)
java -jar "%JAR%" --server.port=%PORT%
