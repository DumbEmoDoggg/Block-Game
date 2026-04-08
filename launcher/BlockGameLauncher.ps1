#Requires -Version 5.1
<#
.SYNOPSIS
    Block Game Launcher – checks for updates from GitHub Releases and launches the game.

.DESCRIPTION
    Place BlockGameLauncher.bat (or this script) in any folder.
    On first run the launcher downloads the game into a 'BlockGame' sub-folder.
    On every subsequent run it checks for a newer release and downloads it only when
    one is available.  Then it starts BlockGame\BlockGame.exe.

    No Java installation is required – the downloaded bundle contains a bundled JRE.

.NOTES
    Repository  : https://github.com/DumbEmoDoggg/Block-Game
    Release tag : latest-build  (rolling; overwritten on every push to main)
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Configuration ─────────────────────────────────────────────────────────────
$REPO          = 'DumbEmoDoggg/Block-Game'
$RELEASE_TAG   = 'latest-build'
$BUNDLE_ASSET  = 'BlockGame-windows-bundled.zip'
$VERSION_ASSET = 'version.txt'

$LauncherDir   = $PSScriptRoot
$GameDir       = Join-Path $LauncherDir 'BlockGame'
$GameExe       = Join-Path $GameDir     'BlockGame.exe'
$LocalVerFile  = Join-Path $LauncherDir 'game-version.txt'
$TempZip       = Join-Path $LauncherDir 'BlockGame-update.zip'
# ──────────────────────────────────────────────────────────────────────────────

function Write-Status([string]$msg) {
    Write-Host "[Launcher] $msg"
}

function Get-ReleaseInfo {
    $uri     = "https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG"
    $headers = @{
        'User-Agent' = 'BlockGame-Launcher'
        'Accept'     = 'application/vnd.github+json'
    }
    return Invoke-RestMethod -Uri $uri -Headers $headers -TimeoutSec 15
}

function Find-Asset([psobject]$release, [string]$name) {
    return $release.assets | Where-Object { $_.name -eq $name } | Select-Object -First 1
}

function Get-RemoteVersion([psobject]$release) {
    $asset = Find-Asset $release $VERSION_ASSET
    if ($null -eq $asset) { return $null }
    $raw = Invoke-RestMethod -Uri $asset.browser_download_url `
               -Headers @{ 'User-Agent' = 'BlockGame-Launcher' } `
               -TimeoutSec 10
    return $raw.ToString().Trim()
}

function Get-LocalVersion {
    if (Test-Path $LocalVerFile) {
        return (Get-Content $LocalVerFile -Raw).Trim()
    }
    return ''
}

function Invoke-Download([string]$url, [string]$dest) {
    Write-Status "Downloading update…"
    $wc = New-Object System.Net.WebClient
    $wc.Headers.Add('User-Agent', 'BlockGame-Launcher')

    # Show a simple byte-count progress (no external cmdlets needed)
    $wc.add_DownloadProgressChanged({
        param($s, $e)
        $pct = $e.ProgressPercentage
        if ($pct -ge 0) {
            $mb = [math]::Round($e.BytesReceived / 1MB, 1)
            Write-Progress -Activity 'Downloading Block Game' `
                           -Status "${pct}%  (${mb} MB)" `
                           -PercentComplete $pct
        }
    })

    $done = $false
    $wc.add_DownloadFileCompleted({ $done = $true })
    $wc.DownloadFileAsync([uri]$url, $dest)

    while (-not $done) { Start-Sleep -Milliseconds 200 }
    Write-Progress -Activity 'Downloading Block Game' -Completed
    $wc.Dispose()
}

function Expand-Bundle([string]$zipPath) {
    Write-Status "Extracting update…"
    # Remove old game folder so stale files do not linger
    if (Test-Path $GameDir) {
        Remove-Item $GameDir -Recurse -Force
    }
    Expand-Archive -Path $zipPath -DestinationPath $LauncherDir -Force
}

# ── Main ──────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "  ╔══════════════════════════════╗"
Write-Host "  ║       Block Game Launcher    ║"
Write-Host "  ╚══════════════════════════════╝"
Write-Host ""

$needsDownload = $false

try {
    Write-Status "Checking for updates…"
    $release       = Get-ReleaseInfo
    $remoteVersion = Get-RemoteVersion $release
    $localVersion  = Get-LocalVersion

    if ($null -eq $remoteVersion) {
        Write-Status "WARNING: Could not read remote version – skipping update check."
    } elseif ($remoteVersion -ne $localVersion -or -not (Test-Path $GameExe)) {
        Write-Status "New version available: $remoteVersion  (local: $(if ($localVersion) { $localVersion } else { 'none' }))"
        $needsDownload = $true
    } else {
        Write-Status "Game is up to date ($localVersion)."
    }

    if ($needsDownload) {
        $bundleAsset = Find-Asset $release $BUNDLE_ASSET
        if ($null -eq $bundleAsset) {
            throw "Release '$RELEASE_TAG' has no asset named '$BUNDLE_ASSET'."
        }

        try {
            Invoke-Download $bundleAsset.browser_download_url $TempZip
            Expand-Bundle $TempZip
            Set-Content -Path $LocalVerFile -Value $remoteVersion -NoNewline
            Write-Status "Update complete!"
        } finally {
            if (Test-Path $TempZip) { Remove-Item $TempZip -Force }
        }
    }
} catch {
    Write-Host ""
    Write-Host "  [WARNING] Update check failed: $_" -ForegroundColor Yellow
    Write-Host "  The launcher will try to start the game with whatever is currently installed." -ForegroundColor Yellow
    Write-Host ""
}

# ── Launch ────────────────────────────────────────────────────────────────────
if (-not (Test-Path $GameExe)) {
    Write-Host ""
    Write-Host "  [ERROR] Game executable not found: $GameExe" -ForegroundColor Red
    Write-Host "  Please make sure you can reach the internet so the launcher can download the game." -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Status "Launching Block Game…"
Write-Host ""
Start-Process -FilePath $GameExe -WorkingDirectory $GameDir
