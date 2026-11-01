@echo off
rem Speak My Life Voice Center - Windows double-click launcher.
rem Runs launch.ps1 hidden; first run auto-installs the AI environment.
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0launch.ps1"
