@echo off
:: Block Game Launcher
:: Double-click this file to check for updates and start the game.
:: PowerShell 5.1+ is required (included with Windows 10 / Windows Server 2016+).
::
:: If BlockGameLauncher.ps1 is missing it is downloaded automatically from
:: the latest-build release, so this .bat file can be used as a standalone
:: bootstrap — no need to download both files manually.

:: Change to the directory that contains this .bat file.
:: This avoids failures caused by spaces in the folder path (e.g. "My Games").
cd /d "%~dp0"

:: Auto-download the PowerShell launcher script if it is not present.
if not exist "BlockGameLauncher.ps1" (
    echo [Launcher] BlockGameLauncher.ps1 not found. Downloading...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
        "Invoke-WebRequest -Uri 'https://github.com/DumbEmoDoggg/Block-Game/releases/download/latest-build/BlockGameLauncher.ps1' -OutFile 'BlockGameLauncher.ps1' -UseBasicParsing"
    if not exist "BlockGameLauncher.ps1" (
        echo.
        echo [ERROR] Could not download BlockGameLauncher.ps1
        echo Please download both launcher files from:
        echo   https://github.com/DumbEmoDoggg/Block-Game/releases/tag/latest-build
        pause
        exit /b 1
    )
    echo [Launcher] Downloaded successfully.
)

:: -STA is required by Windows Forms (used for the launcher GUI).
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File "BlockGameLauncher.ps1"
if %ERRORLEVEL% NEQ 0 pause
