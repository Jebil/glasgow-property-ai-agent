@echo off
:: ============================================================================
:: Glasgow Property AI Agent - One-Click Launcher
:: Runs the PowerShell launcher with ExecutionPolicy Bypass
:: ============================================================================
title Glasgow Property AI Agent Launcher
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=C:\Dev\glasgow-property-ai-agent\run-app.ps1"

if not exist "!PS_SCRIPT!" (
    set "PS_SCRIPT=%SCRIPT_DIR%run-app.ps1"
)

if not exist "!PS_SCRIPT!" (
    echo [ERROR] Could not find run-app.ps1 at "!PS_SCRIPT!".
    pause
    exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "!PS_SCRIPT!" %*

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Launcher finished with exit code %ERRORLEVEL%.
    pause
)

