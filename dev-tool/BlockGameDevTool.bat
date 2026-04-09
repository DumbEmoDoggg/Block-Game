@echo off
:: Block Game Dev Tool
:: Double-click this file to launch the Block Game content creation tool.
:: PowerShell 5.1+ is required (included with Windows 10 / Windows Server 2016+).
::
:: If BlockGameDevTool.ps1 is missing it is downloaded automatically from
:: the repository, so this .bat file can be used as a standalone bootstrap.

:: Change to the directory that contains this .bat file.
cd /d "%~dp0"

:: Auto-download the PowerShell dev tool script if it is not present.
if not exist "BlockGameDevTool.ps1" (
    echo [DevTool] BlockGameDevTool.ps1 not found. Downloading...
    powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
        "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/DumbEmoDoggg/Block-Game/main/dev-tool/BlockGameDevTool.ps1' -OutFile 'BlockGameDevTool.ps1' -UseBasicParsing"
    if errorlevel 1 (
        echo [DevTool] ERROR: Failed to download BlockGameDevTool.ps1
        echo [DevTool] Please download it manually from:
        echo [DevTool]   https://github.com/DumbEmoDoggg/Block-Game/blob/main/dev-tool/BlockGameDevTool.ps1
        pause
        exit /b 1
    )
    echo [DevTool] Download complete.
)

:: Launch the PowerShell GUI script in STA mode.
powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0BlockGameDevTool.ps1"
