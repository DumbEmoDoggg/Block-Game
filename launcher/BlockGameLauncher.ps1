#Requires -Version 5.1
<#
.SYNOPSIS
    Block Game Launcher (GUI) — checks for updates from GitHub Releases and
    launches the game with a dark-themed Windows Forms interface.

.DESCRIPTION
    Place BlockGameLauncher.bat (or this script) in any folder.
    On first run the launcher downloads the game into a 'BlockGame' sub-folder.
    On every subsequent run it checks for a newer release and downloads it only
    when one is available.  Then it starts BlockGame\BlockGame.exe.

    No Java installation is required — the downloaded bundle contains a bundled JRE.

.NOTES
    Repository  : https://github.com/DumbEmoDoggg/Block-Game
    Release tag : latest-build  (rolling; overwritten on every push to main)
#>

# Windows Forms requires STA (single-threaded apartment) mode.
# If this script is invoked without -STA, restart itself in STA mode.
if ([System.Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') {
    $selfPath = $MyInvocation.MyCommand.Path
    if ($selfPath) {
        Start-Process powershell.exe `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -STA -File `"$selfPath`""
    }
    exit
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ── Configuration ─────────────────────────────────────────────────────────────
$REPO         = 'DumbEmoDoggg/Block-Game'
$RELEASE_TAG  = 'latest-build'
$BUNDLE_ASSET = 'BlockGame-windows-bundled.zip'
$VER_ASSET    = 'version.txt'
$LauncherDir  = if ($PSScriptRoot) { $PSScriptRoot } else { $MyInvocation.MyCommand.Path | Split-Path -Parent }
$GameDir      = Join-Path $LauncherDir 'BlockGame'
$GameExe      = Join-Path $GameDir     'BlockGame.exe'
$LocalVerFile = Join-Path $LauncherDir 'game-version.txt'
$TempZip      = Join-Path $LauncherDir 'BlockGame-update.zip'

# ── Thread-safe state (main UI thread <-> background runspace) ─────────────────
$sync = [hashtable]::Synchronized(@{
    # Append [pscustomobject]@{msg=...; level=...} entries from background thread.
    Log       = [System.Collections.ArrayList]::Synchronized(
                    [System.Collections.ArrayList]::new())
    Progress  = [int]0   # 0-100; 0 keeps the Marquee style active
    Done      = $false
    GameReady = $false
})

# ── Helpers ────────────────────────────────────────────────────────────────────
function New-Label([string]$text, [int]$size, [System.Drawing.FontStyle]$style,
                   [System.Drawing.Color]$color, [int]$x, [int]$y) {
    $lbl           = New-Object System.Windows.Forms.Label
    $lbl.Text      = $text
    $lbl.Font      = New-Object System.Drawing.Font('Segoe UI', $size, $style)
    $lbl.ForeColor = $color
    $lbl.AutoSize  = $true
    $lbl.Location  = New-Object System.Drawing.Point($x, $y)
    return $lbl
}

# ── Form ───────────────────────────────────────────────────────────────────────
$form                = New-Object System.Windows.Forms.Form
$form.Text           = 'Block Game Launcher'
$form.Size           = New-Object System.Drawing.Size(520, 410)
$form.StartPosition  = 'CenterScreen'
$form.BackColor      = [System.Drawing.Color]::FromArgb(28, 28, 28)
$form.ForeColor      = [System.Drawing.Color]::White
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox    = $false

# Title
$lblTitle = New-Label 'Block Game' 24 Bold `
    ([System.Drawing.Color]::FromArgb(95, 210, 95)) 20 18
$form.Controls.Add($lblTitle)

$lblSub = New-Label 'Launcher' 11 Regular `
    ([System.Drawing.Color]::FromArgb(130, 130, 130)) 25 66
$form.Controls.Add($lblSub)

# Separator line
$sep           = New-Object System.Windows.Forms.Panel
$sep.BackColor = [System.Drawing.Color]::FromArgb(55, 55, 55)
$sep.Location  = New-Object System.Drawing.Point(20, 100)
$sep.Size      = New-Object System.Drawing.Size(470, 1)
$form.Controls.Add($sep)

# Log (RichTextBox for coloured output)
$rtLog             = New-Object System.Windows.Forms.RichTextBox
$rtLog.ReadOnly    = $true
$rtLog.BackColor   = [System.Drawing.Color]::FromArgb(18, 18, 18)
$rtLog.ForeColor   = [System.Drawing.Color]::FromArgb(195, 195, 195)
$rtLog.Font        = New-Object System.Drawing.Font('Consolas', 9)
$rtLog.Location    = New-Object System.Drawing.Point(20, 110)
$rtLog.Size        = New-Object System.Drawing.Size(470, 168)
$rtLog.BorderStyle = 'None'
$rtLog.ScrollBars  = 'Vertical'
$form.Controls.Add($rtLog)

# Progress bar
$prog          = New-Object System.Windows.Forms.ProgressBar
$prog.Location = New-Object System.Drawing.Point(20, 292)
$prog.Size     = New-Object System.Drawing.Size(470, 16)
$prog.Style    = 'Marquee'
$prog.MarqueeAnimationSpeed = 25
$form.Controls.Add($prog)

# Status label
$lblStatus           = New-Object System.Windows.Forms.Label
$lblStatus.Text      = 'Starting...'
$lblStatus.Font      = New-Object System.Drawing.Font('Segoe UI', 9)
$lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(140, 140, 140)
$lblStatus.AutoSize  = $true
$lblStatus.Location  = New-Object System.Drawing.Point(20, 314)
$form.Controls.Add($lblStatus)

# Play button
$btnPlay                          = New-Object System.Windows.Forms.Button
$btnPlay.Text                     = 'Play'
$btnPlay.Font                     = New-Object System.Drawing.Font('Segoe UI', 12, [System.Drawing.FontStyle]::Bold)
$btnPlay.BackColor                = [System.Drawing.Color]::FromArgb(50, 120, 50)
$btnPlay.ForeColor                = [System.Drawing.Color]::White
$btnPlay.FlatStyle                = 'Flat'
$btnPlay.FlatAppearance.BorderSize = 0
$btnPlay.Location                 = New-Object System.Drawing.Point(370, 348)
$btnPlay.Size                     = New-Object System.Drawing.Size(120, 40)
$btnPlay.Enabled                  = $false
$form.Controls.Add($btnPlay)

# ── UI log helper (call from UI thread only) ───────────────────────────────────
function Add-LogEntry([string]$msg, [string]$level) {
    $color = switch ($level) {
        'warn'  { [System.Drawing.Color]::FromArgb(220, 190,  60) }
        'error' { [System.Drawing.Color]::FromArgb(220,  80,  80) }
        'ok'    { [System.Drawing.Color]::FromArgb( 95, 210,  95) }
        default { [System.Drawing.Color]::FromArgb(195, 195, 195) }
    }
    $rtLog.SelectionStart  = $rtLog.TextLength
    $rtLog.SelectionLength = 0
    $rtLog.SelectionColor  = $color
    $rtLog.AppendText("$msg`n")
    $rtLog.ScrollToCaret()
}

# ── Background runspace (network + file work) ──────────────────────────────────
$rs = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace()
$rs.Open()
foreach ($v in @('sync','REPO','RELEASE_TAG','BUNDLE_ASSET','VER_ASSET',
                  'LauncherDir','GameDir','GameExe','LocalVerFile','TempZip')) {
    $rs.SessionStateProxy.SetVariable($v, (Get-Variable $v -ValueOnly))
}

$ps = [System.Management.Automation.PowerShell]::Create()
$ps.Runspace = $rs
$null = $ps.AddScript({
    function Log([string]$m, [string]$l = 'info') {
        $null = $sync.Log.Add([pscustomobject]@{ msg = $m; level = $l })
    }

    Log 'Checking for updates...'
    $sync.Progress = 5

    try {
        $hdrs    = @{ 'User-Agent' = 'BlockGame-Launcher'
                      'Accept'     = 'application/vnd.github+json' }
        $release = Invoke-RestMethod `
                       -Uri     "https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG" `
                       -Headers $hdrs -TimeoutSec 15

        $verAsset  = $release.assets | Where-Object name -eq $VER_ASSET |
                         Select-Object -First 1
        $remoteVer = if ($verAsset) {
            (Invoke-RestMethod -Uri $verAsset.browser_download_url `
                -Headers @{ 'User-Agent' = 'BlockGame-Launcher' } `
                -TimeoutSec 10).ToString().Trim()
        } else { $null }

        $localVer = if (Test-Path $LocalVerFile) {
            (Get-Content $LocalVerFile -Raw).Trim()
        } else { '' }

        $sync.Progress = 20

        if ($null -eq $remoteVer) {
            Log 'WARNING: Could not determine remote version — skipping update.' 'warn'
            $sync.Progress = 100
        } elseif ($remoteVer -ne $localVer -or -not (Test-Path $GameExe)) {
            $ins = if ($localVer) { $localVer } else { 'none' }
            Log "New version available: $remoteVer  (installed: $ins)"

            $bundleAsset = $release.assets | Where-Object name -eq $BUNDLE_ASSET |
                               Select-Object -First 1
            if (-not $bundleAsset) {
                throw "Asset '$BUNDLE_ASSET' not found in release '$RELEASE_TAG'."
            }

            Log 'Downloading update...'
            $sync.Progress = 30
            Invoke-WebRequest -Uri $bundleAsset.browser_download_url -OutFile $TempZip `
                -UseBasicParsing -Headers @{ 'User-Agent' = 'BlockGame-Launcher' }

            $sync.Progress = 75
            Log 'Extracting...'
            if (Test-Path $GameDir) { Remove-Item $GameDir -Recurse -Force }
            Expand-Archive -Path $TempZip -DestinationPath $LauncherDir -Force
            if (Test-Path $TempZip) { Remove-Item $TempZip -Force }
            Set-Content -Path $LocalVerFile -Value $remoteVer -NoNewline

            $sync.Progress = 100
            Log "Update complete!  Version $remoteVer installed." 'ok'
        } else {
            $sync.Progress = 100
            Log "Game is up to date ($localVer)." 'ok'
        }
    } catch {
        Log "Update check failed: $_" 'warn'
        Log 'Will try to launch the currently installed version.' 'warn'
        if (Test-Path $TempZip) { Remove-Item $TempZip -Force -ErrorAction SilentlyContinue }
        $sync.Progress = 100
    }

    $sync.GameReady = (Test-Path $GameExe)
    $sync.Done      = $true
})

# Start background work before Application::Run so work begins immediately;
# results accumulate in $sync and are flushed by the timer after the form shows.
$script:bgHandle    = $ps.BeginInvoke()
$script:logOffset   = 0

# ── Poll timer (runs on UI thread every 80 ms) ─────────────────────────────────
$timer          = New-Object System.Windows.Forms.Timer
$timer.Interval = 80
$timer.Add_Tick({
    # Drain new log entries from background runspace
    $count = $sync.Log.Count
    while ($script:logOffset -lt $count) {
        $entry = $sync.Log[$script:logOffset]
        Add-LogEntry $entry.msg $entry.level
        $script:logOffset++
    }

    # Update progress bar
    $pct = [int]$sync.Progress
    if ($pct -gt 0) {
        $prog.Style = 'Continuous'
        $prog.Value = [Math]::Min($pct, 100)
    }

    # Update status text
    $lblStatus.Text = if     ($pct -lt 25)  { 'Checking for updates...' }
                      elseif ($pct -lt 80)  { 'Downloading update...'   }
                      elseif ($pct -lt 100) { 'Extracting...'           }
                      elseif ($sync.GameReady) { 'Ready to play!'       }
                      else                  { 'Finished.'               }

    # React when background work completes
    if ($sync.Done) {
        $timer.Stop()
        $ps.EndInvoke($script:bgHandle) | Out-Null
        $ps.Dispose()
        $rs.Close()
        $rs.Dispose()

        if ($sync.GameReady) {
            $btnPlay.Enabled   = $true
            $btnPlay.BackColor = [System.Drawing.Color]::FromArgb(55, 150, 55)
        } else {
            $lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(220, 80, 80)
            $lblStatus.Text      = 'Game not found — check log for details.'
            Add-LogEntry '' 'info'
            Add-LogEntry 'ERROR: Game executable not found.' 'error'
            Add-LogEntry "  Expected: $GameExe" 'error'
            Add-LogEntry '  Ensure internet access and relaunch.' 'error'
        }
    }
})

# ── Event handlers ─────────────────────────────────────────────────────────────
$btnPlay.Add_Click({
    $btnPlay.Enabled = $false
    $btnPlay.Text    = 'Launching...'
    $form.Refresh()
    Start-Process -FilePath $GameExe -WorkingDirectory $GameDir
    $form.Close()
})

$form.Add_FormClosing({
    $timer.Stop()
    $ps.Stop() | Out-Null
    $rs.Close()
    $rs.Dispose()
    if (Test-Path $TempZip) { Remove-Item $TempZip -Force -ErrorAction SilentlyContinue }
})

$form.Add_Shown({ $timer.Start() })

# ── Run ────────────────────────────────────────────────────────────────────────
[System.Windows.Forms.Application]::Run($form)

