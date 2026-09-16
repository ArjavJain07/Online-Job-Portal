@echo off
rem reset-demo.bat - deletes the local demo database and uploaded files, then starts the app so they are re-created.
cd /d "%~dp0"
rem A running app (on any port) keeps data\jobportal.mv.db open, and Windows then refuses to rename the folder.
if exist data-reset rmdir /s /q data-reset
if exist data (
  ren data data-reset >nul 2>nul || (
    echo The database is still in use, so the app is still running. Stop it first ^(Ctrl+C in its window^).
    pause
    exit /b 1
  )
  rmdir /s /q data-reset
)
if exist uploads rmdir /s /q uploads
echo Demo data removed. Starting the app to re-create it...
call run.bat
