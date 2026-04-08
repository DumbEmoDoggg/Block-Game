@echo off
:: Block Game Launcher wrapper
:: Double-click this file to check for updates and start the game.
:: PowerShell 5.1+ is required (included with Windows 10 / Windows Server 2016+).

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BlockGameLauncher.ps1"
if %ERRORLEVEL% NEQ 0 pause
