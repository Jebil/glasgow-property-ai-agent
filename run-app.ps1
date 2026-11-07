<#
.SYNOPSIS
    Launcher script for Glasgow Property AI Agent.
.DESCRIPTION
    Compatible with Windows PowerShell 5.1 and PowerShell 7+.
    Checks prerequisites (Java, Maven, Docker, Ollama), starts the database and Ollama if needed,
    launches Spring Boot via Maven, waits for Tomcat to become healthy, and opens the browser.
#>

[CmdletBinding()]
param(
    [switch]$NoBrowser,
    [switch]$SkipPrereqCheck,
    [switch]$Clean
)

# Use Continue so native command stderr (like Java or Docker diagnostics) does not abort the script
$ErrorActionPreference = "Continue"

# Enable native access for Java 21+ / Java 25 to silence restricted method warnings
$env:MAVEN_OPTS = "--enable-native-access=ALL-UNNAMED"

# Force UTF-8 output where supported
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {}

$AppDir = "C:\Dev\glasgow-property-ai-agent"
if (-not (Test-Path $AppDir)) {
    $AppDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
Set-Location $AppDir

if ($Clean) {
    Write-Host "[+] Cleaning target directory..." -ForegroundColor Cyan
    if (Test-Path "$AppDir\target") {
        try {
            & attrib -r /s "$AppDir\target\*" 2>$null
            Remove-Item -Path "$AppDir\target" -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host "[OK] Cleaned target directory." -ForegroundColor Green
        } catch {
            Write-Host "[!] Could not completely remove target: $_" -ForegroundColor Yellow
        }
    }
}

function Write-Step {
    param([string]$Text)
    Write-Host ""
    Write-Host "[+] $Text" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Text)
    Write-Host "[OK] $Text" -ForegroundColor Green
}

function Write-WarnMsg {
    param([string]$Text)
    Write-Host "[!] $Text" -ForegroundColor Yellow
}

function Write-ErrMsg {
    param([string]$Text)
    Write-Host "[X] $Text" -ForegroundColor Red
}

function Find-CommandPath {
    param([string]$CmdName)
    $cmd = Get-Command $CmdName -ErrorAction SilentlyContinue
    if ($cmd) {
        if ($cmd.Source) { return $cmd.Source }
        if ($cmd.Path) { return $cmd.Path }
        return $cmd.Name
    }
    return $null
}

Clear-Host
Write-Host "==========================================================" -ForegroundColor Magenta
Write-Host "       Glasgow Property AI Agent Launcher" -ForegroundColor White
Write-Host "       Strathclyde Commuter & Family Layout Engine" -ForegroundColor Gray
Write-Host "==========================================================" -ForegroundColor Magenta
Write-Host "Working Directory: $AppDir" -ForegroundColor DarkGray

# 1. Check Java
Write-Step "Checking Java environment..."
$javaBin = Find-CommandPath "java"
if (-not $javaBin) {
    Write-ErrMsg "Java was not found in PATH! Please install Java 21+ or ensure it is in PATH."
    Read-Host "Press Enter to exit..."
    exit 1
}
$javaVer = try { (& java --version | Select-Object -First 1) } catch { "Java detected" }
Write-Success "Java detected: $javaVer"

# 2. Check Maven
Write-Step "Checking Maven..."
$mvnCmd = Find-CommandPath "mvn.cmd"
if (-not $mvnCmd) {
    $mvnCmd = Find-CommandPath "mvn"
}
if (-not $mvnCmd -and (Test-Path "C:\Users\jebil\.m2\apache-maven-3.9.9\bin\mvn.cmd")) {
    $mvnCmd = "C:\Users\jebil\.m2\apache-maven-3.9.9\bin\mvn.cmd"
}
if (-not $mvnCmd) {
    Write-ErrMsg "Maven ('mvn') was not found! Please ensure Maven 3.9+ is installed or in PATH."
    Read-Host "Press Enter to exit..."
    exit 1
}
Write-Success "Using Maven: $mvnCmd"

# 3. Check Docker & PostgreSQL
Write-Step "Checking Docker & PostgreSQL (pgvector)..."
$dockerCmd = Find-CommandPath "docker"
if ($dockerCmd) {
    try {
        $dockerPing = & docker info 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Docker engine is running."
            # Ensure container is up
            $pgRunning = & docker ps --filter "name=glasgow-property-postgres" --filter "status=running" -q
            if (-not $pgRunning) {
                Write-Host "  Starting Postgres pgvector container via docker compose..." -ForegroundColor DarkGray
                & docker compose -f "$AppDir\compose.yaml" up -d
                Start-Sleep -Seconds 2
            }
            Write-Success "PostgreSQL container is up and running."
        } else {
            Write-WarnMsg "Docker engine is not responding. Ensure Docker Desktop is started."
        }
    } catch {
        Write-WarnMsg "Docker check skipped: $_"
    }
} else {
    Write-WarnMsg "Docker CLI not found in PATH; Spring Boot docker compose starter will attempt to manage it."
}

# 4. Check Ollama
Write-Step "Checking Ollama LLM service..."
$ollamaRunning = $false
try {
    $ollamaCheck = Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -Method Get -TimeoutSec 2 -ErrorAction SilentlyContinue
    if ($ollamaCheck) {
        $ollamaRunning = $true
        Write-Success "Ollama service is online on http://127.0.0.1:11434"
        $models = @($ollamaCheck.models | ForEach-Object { $_.name })
        $hasVisionOrGemma = $models | Where-Object { $_ -match "gemma4" -or $_ -match "vision" -or $_ -match "llama3" }
        $hasEmbed = $models | Where-Object { $_ -match "nomic-embed" -or $_ -match "embed" }
        if ($hasVisionOrGemma) {
            Write-Host "  Found vision/ranking model: $($hasVisionOrGemma -join ', ')" -ForegroundColor DarkGray
        } else {
            Write-WarnMsg "Recommended model 'gemma4:e4b' or 'llama3.2-vision' not detected. Ollama will fall back to heuristic extraction."
        }
        if ($hasEmbed) {
            Write-Host "  Found embedding model: $($hasEmbed -join ', ')" -ForegroundColor DarkGray
        } else {
            Write-WarnMsg "Recommended embedding model 'nomic-embed-text' not found. Vector search requires 'ollama pull nomic-embed-text'."
        }
    }
} catch {
    Write-WarnMsg "Ollama is not running on http://127.0.0.1:11434."
    $ollamaBin = Find-CommandPath "ollama"
    if (-not $ollamaBin -and (Test-Path "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe")) {
        $ollamaBin = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
    }
    if ($ollamaBin) {
        Write-Host "  Attempting to start Ollama in background ($ollamaBin serve)..." -ForegroundColor DarkGray
        Start-Process -FilePath $ollamaBin -ArgumentList "serve" -WindowStyle Hidden
        Start-Sleep -Seconds 3
    } else {
        Write-WarnMsg "Could not auto-start Ollama. The app will use deterministic heuristic scoring."
    }
}

# 5. Check if port 8080 is already in use
Write-Step "Checking port 8080..."
$portInUse = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($portInUse) {
    $existingPid = $portInUse.OwningProcess | Select-Object -First 1
    $procObj = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
    $procName = if ($procObj) { $procObj.ProcessName } else { "Unknown" }
    Write-WarnMsg "Port 8080 is currently in use by process $existingPid ($procName)."
    
    # Check if app is already responding
    try {
        $testReq = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/properties/scrape-status" -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($testReq) {
            Write-Success "Glasgow Property AI Agent is ALREADY running on http://localhost:8080!"
            if (-not $NoBrowser) {
                Write-Host "Opening browser..." -ForegroundColor Cyan
                Start-Process "http://localhost:8080"
            }
            Write-Host "`nApp is already active." -ForegroundColor Green
            Read-Host "Press Enter to exit this launcher..."
            exit 0
        }
    } catch {}

    $answer = Read-Host "Kill process $existingPid ($procName) to restart fresh? (Y/N)"
    if ($answer -match "^[Yy]") {
        Stop-Process -Id $existingPid -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
        Write-Success "Process $existingPid terminated."
    } else {
        Write-WarnMsg "Continuing without terminating existing process on port 8080."
    }
}

# Ensure target files don't have stale Windows ReadOnly flags that block Maven resources
if (Test-Path "$AppDir\target") {
    try {
        & attrib -r /s "$AppDir\target\*" 2>$null
    } catch {}
}

# 6. Launch Spring Boot application
Write-Step "Launching Glasgow Property AI Agent via Spring Boot..."
Write-Host "Running: $mvnCmd spring-boot:run" -ForegroundColor DarkGray
Write-Host "Please wait while Spring Boot initializes Tomcat, JPA, and pre-warms local LLM models...`n" -ForegroundColor DarkGray

if (-not $NoBrowser) {
    # Background monitor to open the browser as soon as the server is ready
    Start-Job -ScriptBlock {
        param($AppUrl)
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Seconds 1
            try {
                $req = [System.Net.WebRequest]::Create("$AppUrl/api/properties/scrape-status")
                $req.Timeout = 1500
                $resp = $req.GetResponse()
                if ($resp) {
                    $code = [int]$resp.StatusCode
                    $resp.Close()
                    if ($code -eq 200) {
                        Start-Process $AppUrl
                        break
                    }
                }
            } catch {}
        }
    } -ArgumentList "http://localhost:8080" | Out-Null
}

# Run Maven Spring Boot (keeps console attached for real-time output)
try {
    & $mvnCmd spring-boot:run
} catch {
    Write-ErrMsg "Application execution failed: $_"
} finally {
    Write-Host "`nGlasgow Property AI Agent session closed." -ForegroundColor Gray
    Read-Host "Press Enter to exit..."
}
