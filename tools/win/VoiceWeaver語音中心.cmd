@echo off
rem VoiceWeaver 語音中心 — Windows 雙擊啟動器。
rem 隱藏視窗執行 launch.ps1（不需更動系統 PowerShell 執行原則）。
start "" /min powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0launch.ps1"
