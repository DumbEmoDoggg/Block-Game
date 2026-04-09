#Requires -Version 5.1
<#
.SYNOPSIS
    Block Game Dev Tool (GUI) — create and edit block types, then commit the
    changes directly to the GitHub repository.

.DESCRIPTION
    The tool fetches the current BlockType.java from the GitHub repository,
    lets you add, edit, or remove block entries using a visual form, and then
    commits the updated file back via the GitHub Contents API.

    On every launch it also checks for a newer version of itself using the same
    GitHub Releases mechanism as the game launcher.

.NOTES
    Repository : https://github.com/DumbEmoDoggg/Block-Game
    Branch     : main  (configurable in the Settings tab)
#>

# ── STA guard (Windows Forms requires single-threaded apartment) ───────────────
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

# ── Configuration ──────────────────────────────────────────────────────────────
$REPO               = 'DumbEmoDoggg/Block-Game'
$RELEASE_TAG        = 'latest-build'
$DEVTOOL_VER_ASSET  = 'devtool-version.txt'
$DEVTOOL_PS1_ASSET  = 'BlockGameDevTool.ps1'
$BLOCKTYPE_API_PATH = 'src/main/java/com/blockgame/world/BlockType.java'
$DEFAULT_BRANCH     = 'main'

$ToolDir        = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path $MyInvocation.MyCommand.Path }
$SettingsFile   = Join-Path $ToolDir 'devtool-settings.json'
$LocalVerFile   = Join-Path $ToolDir 'devtool-version.txt'
$SelfPs1Path    = Join-Path $ToolDir 'BlockGameDevTool.ps1'

# ── Colors ─────────────────────────────────────────────────────────────────────
$C_BG       = [System.Drawing.Color]::FromArgb(28,  28,  28)
$C_PANEL    = [System.Drawing.Color]::FromArgb(38,  38,  38)
$C_DARK     = [System.Drawing.Color]::FromArgb(18,  18,  18)
$C_SEP      = [System.Drawing.Color]::FromArgb(55,  55,  55)
$C_FG       = [System.Drawing.Color]::White
$C_DIM      = [System.Drawing.Color]::FromArgb(140, 140, 140)
$C_GREEN    = [System.Drawing.Color]::FromArgb(95,  210,  95)
$C_YELLOW   = [System.Drawing.Color]::FromArgb(220, 190,  60)
$C_RED      = [System.Drawing.Color]::FromArgb(220,  80,  80)
$C_BTN_BG   = [System.Drawing.Color]::FromArgb(55,  55,  55)
$C_BTN_OK   = [System.Drawing.Color]::FromArgb(50,  120,  50)
$C_BTN_BLUE = [System.Drawing.Color]::FromArgb(40,  90,  160)

# ── Script-level state ─────────────────────────────────────────────────────────
$script:Blocks        = [System.Collections.Generic.List[pscustomobject]]::new()
$script:FileSha       = ''          # GitHub blob SHA needed for the PUT update
$script:EditingNew    = $false      # true when the form is for a brand-new block
$script:SelectedIndex = -1

# ── Settings helpers ───────────────────────────────────────────────────────────
function Load-Settings {
    if (Test-Path $SettingsFile) {
        try {
            $raw = Get-Content $SettingsFile -Raw | ConvertFrom-Json
            $tok = ''
            if ($raw.TokenEnc) {
                try {
                    $ss  = $raw.TokenEnc | ConvertTo-SecureString
                    $ptr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($ss)
                    $tok = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
                    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
                } catch { $tok = '' }
            }
            return @{
                Token  = $tok
                Branch = if ($raw.Branch) { $raw.Branch } else { $DEFAULT_BRANCH }
            }
        } catch { }
    }
    return @{ Token = ''; Branch = $DEFAULT_BRANCH }
}

function Save-Settings([string]$token, [string]$branch) {
    $enc = ''
    if ($token) {
        $ss  = ConvertTo-SecureString $token -AsPlainText -Force
        $enc = $ss | ConvertFrom-SecureString
    }
    @{ TokenEnc = $enc; Branch = $branch } | ConvertTo-Json | Set-Content $SettingsFile -Encoding UTF8
}

# ── Block parsing helpers ──────────────────────────────────────────────────────

# Parses all enum constant entries from the content of BlockType.java.
function Parse-BlockTypes([string]$content) {
    $list = [System.Collections.Generic.List[pscustomobject]]::new()

    # Match a single enum entry line, optionally preceded by a single-line /** … */ comment.
    # Pattern segments and their capture groups:
    #   Group 1  – comment text inside /** … */ (optional, single-line only)
    #   Group 2  – enum constant NAME  (e.g. GRASS, COAL_ORE)
    #   Group 3  – integer id
    #   Groups 4-6 – float R, G, B colour components (without the trailing 'f')
    #   Group 7  – solid flag  (true|false)
    #   Group 8  – transparent flag  (true|false)
    #   Group 9  – optional behavior argument: new Something() or null
    #   Group 10 – optional breakable flag  (true|false)
    $pat = '(?m)(?:[ \t]*/\*\*[ \t]*([^\n]*?)[ \t]*\*/[ \t]*\n)?' +  # Group 1: comment
           '[ \t]+([A-Z][A-Z0-9_]*)\s*'                             +  # Group 2: NAME
           '\(\s*(\d+)\s*,'                                          +  # Group 3: id
           '\s*([\d.]+)f\s*,\s*([\d.]+)f\s*,\s*([\d.]+)f\s*,'      +  # Groups 4-6: R G B
           '\s*(true|false)\s*,\s*(true|false)'                      +  # Groups 7-8: solid, transparent
           '(?:\s*,\s*(new\s+\w+\(\)|null))?'                        +  # Group 9: behavior (optional)
           '(?:\s*,\s*(true|false))?\s*\)'                              # Group 10: breakable (optional)

    $re = [regex]::new($pat)

    foreach ($m in $re.Matches($content)) {
        $comment   = $m.Groups[1].Value.Trim()
        $name      = $m.Groups[2].Value
        $id        = [int]$m.Groups[3].Value
        $r         = [float]$m.Groups[4].Value
        $g         = [float]$m.Groups[5].Value
        $b         = [float]$m.Groups[6].Value
        $solid     = $m.Groups[7].Value -eq 'true'
        $transp    = $m.Groups[8].Value -eq 'true'
        $rawBeh    = $m.Groups[9].Value.Trim()
        $behavior  = if ($rawBeh -eq 'null' -or $rawBeh -eq '') { '' } else { $rawBeh }
        $breakable = if ($m.Groups[10].Success -and $m.Groups[10].Value) {
                         $m.Groups[10].Value -eq 'true'
                     } else { $true }

        $null = $list.Add([pscustomobject]@{
            Name        = $name
            Id          = $id
            R           = $r
            G           = $g
            B           = $b
            Solid       = $solid
            Transparent = $transp
            Behavior    = $behavior
            Breakable   = $breakable
            Comment     = $comment
        })
    }
    return $list
}

# Formats a single enum constant line (without trailing comma/semicolon).
function Format-BlockEntry([pscustomobject]$block) {
    $n    = $block.Name.PadRight(8)
    $r    = ('{0:F2}f' -f $block.R)
    $g    = ('{0:F2}f' -f $block.G)
    $b    = ('{0:F2}f' -f $block.B)
    $sol  = if ($block.Solid)       { 'true'  } else { 'false' }
    $trp  = if ($block.Transparent) { 'true'  } else { 'false' }

    $args = "$($block.Id), $r, $g, $b, $sol, $trp"

    if ($block.Behavior -and $block.Behavior -ne '') {
        $args += ", $($block.Behavior)"
        if (-not $block.Breakable) { $args += ', false' }
    } elseif (-not $block.Breakable) {
        $args += ', null, false'
    }

    return "    $n ($args)"
}

# Rebuilds BlockType.java with an updated set of block constants.
function Rebuild-JavaFile([string]$original, [System.Collections.Generic.List[pscustomobject]]$blocks) {
    $sorted = @($blocks | Sort-Object Id)

    # ── Build the new enum-constants section ───────────────────────────────────
    $sb = [System.Text.StringBuilder]::new()
    for ($i = 0; $i -lt $sorted.Count; $i++) {
        $blk    = $sorted[$i]
        $isLast = ($i -eq $sorted.Count - 1)
        $suffix = if ($isLast) { ';' } else { ',' }

        if ($blk.Comment) {
            $null = $sb.AppendLine("    /** $($blk.Comment) */")
        }
        $null = $sb.Append((Format-BlockEntry $blk))
        $null = $sb.AppendLine($suffix)
    }
    $newSection = $sb.ToString().TrimEnd("`r", "`n")

    # ── Replace the old constants section in the original file ─────────────────
    # The section spans from the blank line after "public enum BlockType {" up to
    # (but not including) the blank line + javadoc that starts the fields section.
    $replPat = '(?s)(?<=public enum BlockType \{\s*\n\n)(.*?;)(?=\r?\n\r?\n\s+/\*\*\s*Compact id)'
    $result  = [regex]::Replace($original, $replPat, $newSection)

    if ($result -eq $original) {
        # Fallback: replace from first constant entry to the semicolon-terminated last entry
        $replPat2 = '(?s)(?<=public enum BlockType \{\s*\n\n)(.*?;)(?=\r?\n\r?\n)'
        $result   = [regex]::Replace($original, $replPat2, $newSection)
    }

    return $result
}

# ── GitHub API helpers ─────────────────────────────────────────────────────────
function Get-AuthHeaders([string]$token) {
    $h = @{ 'User-Agent' = 'BlockGame-DevTool'; 'Accept' = 'application/vnd.github+json' }
    if ($token) { $h['Authorization'] = "Bearer $token" }
    return $h
}

# Fetches a file from the repo; returns @{Content=<string>; Sha=<string>} or throws.
function Get-GitHubFile([string]$token, [string]$branch, [string]$filePath) {
    $uri  = "https://api.github.com/repos/$REPO/contents/$filePath`?ref=$branch"
    $resp = Invoke-RestMethod -Uri $uri -Headers (Get-AuthHeaders $token) -TimeoutSec 20
    $raw  = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($resp.content))
    return @{ Content = $raw; Sha = $resp.sha }
}

# Commits updated file content back to the repo via the Contents API.
function Commit-GitHubFile([string]$token, [string]$branch,
                           [string]$filePath, [string]$content,
                           [string]$sha, [string]$message) {
    if (-not $token) { throw 'A GitHub Personal Access Token is required to commit.' }
    $encoded = [System.Convert]::ToBase64String(
                   [System.Text.Encoding]::UTF8.GetBytes($content))
    $body = @{
        message = $message
        content = $encoded
        sha     = $sha
        branch  = $branch
    } | ConvertTo-Json
    $uri = "https://api.github.com/repos/$REPO/contents/$filePath"
    $null = Invoke-RestMethod -Uri $uri -Method Put -Headers (Get-AuthHeaders $token) `
                              -Body $body -ContentType 'application/json' -TimeoutSec 30
}

# Checks the latest-build release for a newer dev-tool version; self-updates if found.
# Returns $true if the tool restarted itself (caller should close the form).
function Check-DevToolUpdate([string]$token) {
    try {
        $hdrs    = Get-AuthHeaders $token
        $release = Invoke-RestMethod `
                       -Uri "https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG" `
                       -Headers $hdrs -TimeoutSec 15

        $verAsset = $release.assets | Where-Object name -eq $DEVTOOL_VER_ASSET |
                        Select-Object -First 1
        if (-not $verAsset) { return $false }   # asset doesn't exist yet — skip

        $remoteVer = (Invoke-RestMethod -Uri $verAsset.browser_download_url `
                          -Headers $hdrs -TimeoutSec 10).ToString().Trim()
        $localVer  = if (Test-Path $LocalVerFile) {
                         (Get-Content $LocalVerFile -Raw).Trim()
                     } else { '' }

        if ($remoteVer -eq $localVer) { return $false }

        $ps1Asset = $release.assets | Where-Object name -eq $DEVTOOL_PS1_ASSET |
                        Select-Object -First 1
        if (-not $ps1Asset) { return $false }

        $tmp = "$SelfPs1Path.tmp"
        Invoke-WebRequest -Uri $ps1Asset.browser_download_url -OutFile $tmp `
            -UseBasicParsing -Headers $hdrs
        Move-Item $tmp $SelfPs1Path -Force
        Set-Content -Path $LocalVerFile -Value $remoteVer -NoNewline
        Start-Process powershell.exe `
            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -STA -File `"$SelfPs1Path`""
        return $true
    } catch {
        # Update check is non-critical — swallow errors
        return $false
    }
}

# ── UI factory helpers ─────────────────────────────────────────────────────────
function New-Label([string]$text, [int]$x, [int]$y,
                   [int]$size = 9, [System.Drawing.FontStyle]$style = 'Regular',
                   [System.Drawing.Color]$color = $C_FG) {
    $l           = New-Object System.Windows.Forms.Label
    $l.Text      = $text
    $l.Font      = New-Object System.Drawing.Font('Segoe UI', $size, $style)
    $l.ForeColor = $color
    $l.AutoSize  = $true
    $l.Location  = New-Object System.Drawing.Point($x, $y)
    return $l
}

function New-TextBox([int]$x, [int]$y, [int]$w, [string]$text = '',
                     [bool]$password = $false) {
    $t              = New-Object System.Windows.Forms.TextBox
    $t.Location     = New-Object System.Drawing.Point($x, $y)
    $t.Size         = New-Object System.Drawing.Size($w, 22)
    $t.BackColor    = $C_DARK
    $t.ForeColor    = $C_FG
    $t.BorderStyle  = 'FixedSingle'
    $t.Font         = New-Object System.Drawing.Font('Segoe UI', 9)
    $t.Text         = $text
    if ($password) { $t.UseSystemPasswordChar = $true }
    return $t
}

function New-Button([string]$text, [int]$x, [int]$y, [int]$w, [int]$h,
                    [System.Drawing.Color]$bg = $C_BTN_BG) {
    $b                             = New-Object System.Windows.Forms.Button
    $b.Text                        = $text
    $b.Font                        = New-Object System.Drawing.Font('Segoe UI', 9)
    $b.BackColor                   = $bg
    $b.ForeColor                   = $C_FG
    $b.FlatStyle                   = 'Flat'
    $b.FlatAppearance.BorderSize   = 0
    $b.Location                    = New-Object System.Drawing.Point($x, $y)
    $b.Size                        = New-Object System.Drawing.Size($w, $h)
    return $b
}

function New-CheckBox([string]$text, [int]$x, [int]$y, [bool]$checked = $false) {
    $c           = New-Object System.Windows.Forms.CheckBox
    $c.Text      = $text
    $c.Font      = New-Object System.Drawing.Font('Segoe UI', 9)
    $c.ForeColor = $C_FG
    $c.BackColor = $C_PANEL
    $c.Location  = New-Object System.Drawing.Point($x, $y)
    $c.AutoSize  = $true
    $c.Checked   = $checked
    return $c
}

function New-NumericUpDown([int]$x, [int]$y, [int]$w,
                           [int]$min = 0, [int]$max = 255, [int]$val = 0) {
    $n              = New-Object System.Windows.Forms.NumericUpDown
    $n.Location     = New-Object System.Drawing.Point($x, $y)
    $n.Size         = New-Object System.Drawing.Size($w, 22)
    $n.Minimum      = $min
    $n.Maximum      = $max
    $n.Value        = $val
    $n.BackColor    = $C_DARK
    $n.ForeColor    = $C_FG
    $n.BorderStyle  = 'FixedSingle'
    $n.Font         = New-Object System.Drawing.Font('Segoe UI', 9)
    return $n
}

function New-ComboBox([int]$x, [int]$y, [int]$w, [string[]]$items) {
    $c              = New-Object System.Windows.Forms.ComboBox
    $c.Location     = New-Object System.Drawing.Point($x, $y)
    $c.Size         = New-Object System.Drawing.Size($w, 22)
    $c.BackColor    = $C_DARK
    $c.ForeColor    = $C_FG
    $c.FlatStyle    = 'Flat'
    $c.Font         = New-Object System.Drawing.Font('Segoe UI', 9)
    $c.DropDownStyle = 'DropDownList'
    foreach ($item in $items) { $null = $c.Items.Add($item) }
    if ($c.Items.Count -gt 0) { $c.SelectedIndex = 0 }
    return $c
}

function Add-LogEntry([System.Windows.Forms.RichTextBox]$rtb,
                      [string]$msg, [string]$level = 'info') {
    $color = switch ($level) {
        'warn'  { $C_YELLOW }
        'error' { $C_RED    }
        'ok'    { $C_GREEN  }
        default { [System.Drawing.Color]::FromArgb(195, 195, 195) }
    }
    $rtb.SelectionStart  = $rtb.TextLength
    $rtb.SelectionLength = 0
    $rtb.SelectionColor  = $color
    $rtb.AppendText("$msg`n")
    $rtb.ScrollToCaret()
}

# ── Settings load ──────────────────────────────────────────────────────────────
$settings = Load-Settings
$script:Token  = $settings.Token
$script:Branch = $settings.Branch

# ── Main form ──────────────────────────────────────────────────────────────────
$form                 = New-Object System.Windows.Forms.Form
$form.Text            = 'Block Game Dev Tool'
$form.Size            = New-Object System.Drawing.Size(860, 660)
$form.StartPosition   = 'CenterScreen'
$form.BackColor       = $C_BG
$form.ForeColor       = $C_FG
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox     = $false

# Title
$form.Controls.Add((New-Label 'Block Game' 20 14 20 Bold $C_GREEN))
$form.Controls.Add((New-Label 'Dev Tool'   20 48 12 Regular $C_DIM))

# Separator
$sepTop           = New-Object System.Windows.Forms.Panel
$sepTop.BackColor = $C_SEP
$sepTop.Location  = New-Object System.Drawing.Point(20, 82)
$sepTop.Size      = New-Object System.Drawing.Size(800, 1)
$form.Controls.Add($sepTop)

# ── Tab control ────────────────────────────────────────────────────────────────
$tabs              = New-Object System.Windows.Forms.TabControl
$tabs.Location     = New-Object System.Drawing.Point(20, 92)
$tabs.Size         = New-Object System.Drawing.Size(802, 510)
$tabs.BackColor    = $C_PANEL
$tabs.ForeColor    = $C_FG
$tabs.Font         = New-Object System.Drawing.Font('Segoe UI', 9)
$form.Controls.Add($tabs)

$tabBlocks   = New-Object System.Windows.Forms.TabPage; $tabBlocks.Text   = 'Blocks';   $tabBlocks.BackColor   = $C_PANEL
$tabSettings = New-Object System.Windows.Forms.TabPage; $tabSettings.Text = 'Settings'; $tabSettings.BackColor = $C_PANEL
$tabs.Controls.Add($tabBlocks)
$tabs.Controls.Add($tabSettings)

# ── Log bar at the bottom ──────────────────────────────────────────────────────
$rtLog            = New-Object System.Windows.Forms.RichTextBox
$rtLog.ReadOnly   = $true
$rtLog.BackColor  = $C_DARK
$rtLog.ForeColor  = [System.Drawing.Color]::FromArgb(195, 195, 195)
$rtLog.Font       = New-Object System.Drawing.Font('Consolas', 8.5)
$rtLog.Location   = New-Object System.Drawing.Point(20, 608)
$rtLog.Size       = New-Object System.Drawing.Size(802, 0)   # hidden until first use
$rtLog.BorderStyle = 'None'
$rtLog.ScrollBars = 'Vertical'
$form.Controls.Add($rtLog)

function Log([string]$msg, [string]$level = 'info') {
    if ($rtLog.Height -eq 0) {
        $rtLog.Height    = 80
        $form.ClientSize = New-Object System.Drawing.Size(860, 705)
    }
    Add-LogEntry $rtLog $msg $level
}

# ══════════════════════════════════════════════════════════════════════════════
# BLOCKS TAB
# ══════════════════════════════════════════════════════════════════════════════

# ── Block list (left panel) ────────────────────────────────────────────────────
$lvBlocks                = New-Object System.Windows.Forms.ListView
$lvBlocks.Location       = New-Object System.Drawing.Point(8, 8)
$lvBlocks.Size           = New-Object System.Drawing.Size(305, 390)
$lvBlocks.View           = 'Details'
$lvBlocks.FullRowSelect  = $true
$lvBlocks.GridLines      = $true
$lvBlocks.BackColor      = $C_DARK
$lvBlocks.ForeColor      = $C_FG
$lvBlocks.BorderStyle    = 'FixedSingle'
$lvBlocks.Font           = New-Object System.Drawing.Font('Consolas', 8.5)
$lvBlocks.MultiSelect    = $false

foreach ($col in @(
    @('Name', 90), @('ID', 38), @('Color', 60), @('Solid', 44), @('Transp', 44), @('Break', 44)
)) {
    $c        = New-Object System.Windows.Forms.ColumnHeader
    $c.Text   = $col[0]
    $c.Width  = $col[1]
    $null = $lvBlocks.Columns.Add($c)
}
$tabBlocks.Controls.Add($lvBlocks)

# ── Toolbar under the list ─────────────────────────────────────────────────────
$btnFetch     = New-Button 'Fetch from GitHub'  8 408 145 28 $C_BTN_BLUE
$btnNewBlock  = New-Button '+ New Block'       8 444 100 28 $C_BTN_BG
$btnDelBlock  = New-Button '✕ Delete'        116 444  90 28 $C_RED

$tabBlocks.Controls.Add($btnFetch)
$tabBlocks.Controls.Add($btnNewBlock)
$tabBlocks.Controls.Add($btnDelBlock)

# ── Edit panel (right side) ────────────────────────────────────────────────────
$pnlEdit           = New-Object System.Windows.Forms.Panel
$pnlEdit.Location  = New-Object System.Drawing.Point(322, 8)
$pnlEdit.Size      = New-Object System.Drawing.Size(456, 460)
$pnlEdit.BackColor = $C_PANEL
$tabBlocks.Controls.Add($pnlEdit)

function Add-EditLabel([string]$t, [int]$x, [int]$y) {
    $pnlEdit.Controls.Add((New-Label $t $x $y 9 Regular $C_DIM))
}

Add-EditLabel 'Block Name'  10  10
$txtName = New-TextBox 10 28 160
$pnlEdit.Controls.Add($txtName)

Add-EditLabel 'Block ID'  186  10
$nudId = New-NumericUpDown 186 28 70 0 254 0
$pnlEdit.Controls.Add($nudId)

# Color row
Add-EditLabel 'Red (0-255)'  10  66
Add-EditLabel 'Green (0-255)' 114 66
Add-EditLabel 'Blue (0-255)'  218 66

$nudR = New-NumericUpDown  10 84 92 0 255 0
$nudG = New-NumericUpDown 114 84 92 0 255 0
$nudB = New-NumericUpDown 218 84 92 0 255 0
$pnlEdit.Controls.Add($nudR)
$pnlEdit.Controls.Add($nudG)
$pnlEdit.Controls.Add($nudB)

# Color preview
$pnlColor          = New-Object System.Windows.Forms.Panel
$pnlColor.Location = New-Object System.Drawing.Point(322, 80)
$pnlColor.Size     = New-Object System.Drawing.Size(48, 32)
$pnlColor.BackColor = [System.Drawing.Color]::Black
$pnlColor.BorderStyle = 'FixedSingle'
$pnlEdit.Controls.Add($pnlColor)
Add-EditLabel 'Preview' 322 66

function Update-ColorPreview {
    $r = [int]$nudR.Value
    $g = [int]$nudG.Value
    $b = [int]$nudB.Value
    $pnlColor.BackColor = [System.Drawing.Color]::FromArgb($r, $g, $b)
}

# Flags
$chkSolid    = New-CheckBox 'Solid'       10 128
$chkTransp   = New-CheckBox 'Transparent' 10 152
$chkBreak    = New-CheckBox 'Breakable'   10 176
$chkBreak.Checked = $true
$pnlEdit.Controls.Add($chkSolid)
$pnlEdit.Controls.Add($chkTransp)
$pnlEdit.Controls.Add($chkBreak)

# Behavior
Add-EditLabel 'Behavior' 10 210
$cmbBehavior = New-ComboBox 10 228 230 @(
    '(none)',
    'new FallingBlockBehavior()',
    'new WaterBehavior()'
)
$pnlEdit.Controls.Add($cmbBehavior)

# Description / comment
Add-EditLabel 'Description (optional comment)' 10 268
$txtComment = New-TextBox 10 286 420
$pnlEdit.Controls.Add($txtComment)

# Apply button
$btnApply = New-Button 'Apply Changes' 10 330 140 30 $C_BTN_OK
$pnlEdit.Controls.Add($btnApply)

# Commit button
$btnCommit = New-Button 'Commit to GitHub' 10 375 160 30 $C_BTN_BLUE
$pnlEdit.Controls.Add($btnCommit)

$lblEditStatus            = New-Object System.Windows.Forms.Label
$lblEditStatus.Font       = New-Object System.Drawing.Font('Segoe UI', 8.5)
$lblEditStatus.ForeColor  = $C_DIM
$lblEditStatus.AutoSize   = $true
$lblEditStatus.Location   = New-Object System.Drawing.Point(180, 382)
$pnlEdit.Controls.Add($lblEditStatus)

# ══════════════════════════════════════════════════════════════════════════════
# SETTINGS TAB
# ══════════════════════════════════════════════════════════════════════════════

$tabSettings.Controls.Add((New-Label 'GitHub Personal Access Token' 10 14 9 Regular $C_DIM))
$tabSettings.Controls.Add((New-Label '(requires repo / contents:write scope)' 10 30 8 Regular $C_DIM))

$txtToken = New-TextBox 10 52 480 '' $true
$txtToken.Text = $script:Token
$tabSettings.Controls.Add($txtToken)

$btnShowToken          = New-Button 'Show' 502 52 58 22 $C_BTN_BG
$tabSettings.Controls.Add($btnShowToken)

$tabSettings.Controls.Add((New-Label 'Target Branch' 10 92 9 Regular $C_DIM))
$txtBranch = New-TextBox 10 110 180 $script:Branch
$tabSettings.Controls.Add($txtBranch)

$btnSaveSettings = New-Button 'Save Settings' 10 150 130 30 $C_BTN_OK
$tabSettings.Controls.Add($btnSaveSettings)

$lblSettingsStatus            = New-Object System.Windows.Forms.Label
$lblSettingsStatus.Font       = New-Object System.Drawing.Font('Segoe UI', 9)
$lblSettingsStatus.ForeColor  = $C_DIM
$lblSettingsStatus.AutoSize   = $true
$lblSettingsStatus.Location   = New-Object System.Drawing.Point(10, 192)
$tabSettings.Controls.Add($lblSettingsStatus)

$tabSettings.Controls.Add((New-Label 'How to create a token:' 10 230 9 Bold $C_FG))
$tabSettings.Controls.Add((New-Label "1. Go to GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)" 10 252 9))
$tabSettings.Controls.Add((New-Label '2. Click "Generate new token (classic)"' 10 272 9))
$tabSettings.Controls.Add((New-Label '3. Give it a name and tick the "repo" scope' 10 292 9))
$tabSettings.Controls.Add((New-Label '4. Copy the token and paste it above, then click Save Settings' 10 312 9))

# ══════════════════════════════════════════════════════════════════════════════
# HELPER: refresh the block list view
# ══════════════════════════════════════════════════════════════════════════════
function Refresh-ListView {
    $lvBlocks.Items.Clear()
    foreach ($blk in ($script:Blocks | Sort-Object Id)) {
        $hex  = '#{0:X2}{1:X2}{2:X2}' -f ([int]($blk.R * 255)),([int]($blk.G * 255)),([int]($blk.B * 255))
        $sol  = if ($blk.Solid)       { 'yes' } else { 'no' }
        $trp  = if ($blk.Transparent) { 'yes' } else { 'no' }
        $brk  = if ($blk.Breakable)   { 'yes' } else { 'no' }
        $item = New-Object System.Windows.Forms.ListViewItem($blk.Name)
        $null = $item.SubItems.Add($blk.Id.ToString())
        $null = $item.SubItems.Add($hex)
        $null = $item.SubItems.Add($sol)
        $null = $item.SubItems.Add($trp)
        $null = $item.SubItems.Add($brk)
        $item.Tag = $blk.Name
        $null = $lvBlocks.Items.Add($item)
    }
}

# Populates the edit panel from a block object
function Populate-EditPanel([pscustomobject]$blk) {
    $txtName.Text          = $blk.Name
    $nudId.Value           = $blk.Id
    $nudR.Value            = [Math]::Round($blk.R * 255)
    $nudG.Value            = [Math]::Round($blk.G * 255)
    $nudB.Value            = [Math]::Round($blk.B * 255)
    $chkSolid.Checked      = $blk.Solid
    $chkTransp.Checked     = $blk.Transparent
    $chkBreak.Checked      = $blk.Breakable
    $txtComment.Text       = $blk.Comment

    $behIdx = switch ($blk.Behavior) {
        'new FallingBlockBehavior()' { 1 }
        'new WaterBehavior()'        { 2 }
        default                      { 0 }
    }
    $cmbBehavior.SelectedIndex = $behIdx
    Update-ColorPreview
}

# Clears the edit panel for a new block
function Clear-EditPanel {
    $script:EditingNew = $true
    $txtName.Text      = ''
    $nudId.Value       = if ($script:Blocks.Count -gt 0) {
                             ($script:Blocks | Measure-Object Id -Maximum).Maximum + 1
                         } else { 1 }
    $nudR.Value        = 128
    $nudG.Value        = 128
    $nudB.Value        = 128
    $chkSolid.Checked  = $true
    $chkTransp.Checked = $false
    $chkBreak.Checked  = $true
    $txtComment.Text   = ''
    $cmbBehavior.SelectedIndex = 0
    Update-ColorPreview
    $lblEditStatus.Text = 'New block — fill in the details and click Apply'
}

# Reads current edit panel values into a block object
function Get-EditPanelValues {
    return [pscustomobject]@{
        Name        = $txtName.Text.Trim().ToUpper() -replace '\s+', '_'
        Id          = [int]$nudId.Value
        R           = [float]([int]$nudR.Value / 255.0)
        G           = [float]([int]$nudG.Value / 255.0)
        B           = [float]([int]$nudB.Value / 255.0)
        Solid       = $chkSolid.Checked
        Transparent = $chkTransp.Checked
        Breakable   = $chkBreak.Checked
        Behavior    = switch ($cmbBehavior.SelectedIndex) {
                          1 { 'new FallingBlockBehavior()' }
                          2 { 'new WaterBehavior()'        }
                          default { '' }
                      }
        Comment     = $txtComment.Text.Trim()
    }
}

# ══════════════════════════════════════════════════════════════════════════════
# EVENT HANDLERS
# ══════════════════════════════════════════════════════════════════════════════

# Color spinners → update preview
$colorChanged = { Update-ColorPreview }
$nudR.Add_ValueChanged($colorChanged)
$nudG.Add_ValueChanged($colorChanged)
$nudB.Add_ValueChanged($colorChanged)

# Show/hide token
$btnShowToken.Add_Click({
    $txtToken.UseSystemPasswordChar = -not $txtToken.UseSystemPasswordChar
    $btnShowToken.Text = if ($txtToken.UseSystemPasswordChar) { 'Show' } else { 'Hide' }
})

# Save settings
$btnSaveSettings.Add_Click({
    $script:Token  = $txtToken.Text.Trim()
    $script:Branch = if ($txtBranch.Text.Trim()) { $txtBranch.Text.Trim() } else { $DEFAULT_BRANCH }
    Save-Settings $script:Token $script:Branch
    $lblSettingsStatus.ForeColor = $C_GREEN
    $lblSettingsStatus.Text      = 'Settings saved.'
})

# ListView selection
$lvBlocks.Add_SelectedIndexChanged({
    if ($lvBlocks.SelectedItems.Count -eq 0) { return }
    $name = $lvBlocks.SelectedItems[0].Tag
    $blk  = $script:Blocks | Where-Object Name -eq $name | Select-Object -First 1
    if ($blk) {
        $script:EditingNew    = $false
        $script:SelectedIndex = $script:Blocks.IndexOf($blk)
        Populate-EditPanel $blk
        $lblEditStatus.Text = "Editing: $name"
    }
})

# New block
$btnNewBlock.Add_Click({ Clear-EditPanel })

# Delete block
$btnDelBlock.Add_Click({
    if ($lvBlocks.SelectedItems.Count -eq 0) {
        Log 'Select a block in the list first.' 'warn'
        return
    }
    $name = $lvBlocks.SelectedItems[0].Tag
    $blk  = $script:Blocks | Where-Object Name -eq $name | Select-Object -First 1
    if (-not $blk) { return }
    $ans  = [System.Windows.Forms.MessageBox]::Show(
        "Delete block '$name'? This cannot be undone until you fetch again.",
        'Confirm Delete',
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Warning)
    if ($ans -eq 'Yes') {
        $null = $script:Blocks.Remove($blk)
        Refresh-ListView
        Clear-EditPanel
        Log "Block '$name' removed from the in-memory list." 'warn'
    }
})

# Apply changes
$btnApply.Add_Click({
    $v = Get-EditPanelValues
    if (-not $v.Name -or $v.Name -notmatch '^[A-Z][A-Z0-9_]*$') {
        Log 'Block name must start with a letter and contain only A-Z, 0-9, _.' 'error'
        return
    }
    # Duplicate ID check (when editing, skip the block currently being edited)
    if (-not $script:EditingNew) {
        $dup = $script:Blocks | Where-Object { $_.Id -eq $v.Id -and $_.Name -ne $lvBlocks.SelectedItems[0].Tag } | Select-Object -First 1
    } else {
        $dup = $script:Blocks | Where-Object Id -eq $v.Id | Select-Object -First 1
    }
    if ($dup) {
        Log "ID $($v.Id) is already used by '$($dup.Name)'. Choose a different ID." 'error'
        return
    }

    if ($script:EditingNew) {
        # Name uniqueness check
        if ($script:Blocks | Where-Object Name -eq $v.Name) {
            Log "A block named '$($v.Name)' already exists." 'error'
            return
        }
        $null = $script:Blocks.Add($v)
        Log "Block '$($v.Name)' added." 'ok'
    } else {
        $oldName = $lvBlocks.SelectedItems[0].Tag
        $existing = $script:Blocks | Where-Object Name -eq $oldName | Select-Object -First 1
        if ($existing) {
            $idx = $script:Blocks.IndexOf($existing)
            $script:Blocks[$idx] = $v
            Log "Block '$oldName' updated to '$($v.Name)'." 'ok'
        }
    }

    $script:EditingNew = $false
    Refresh-ListView
    # Re-select the newly added/edited item
    foreach ($item in $lvBlocks.Items) {
        if ($item.Tag -eq $v.Name) { $item.Selected = $true; break }
    }
    $lblEditStatus.Text = "Saved: $($v.Name)"
})

# Fetch from GitHub
$btnFetch.Add_Click({
    $btnFetch.Enabled = $false
    $btnFetch.Text    = 'Fetching...'
    $form.Refresh()
    try {
        $result = Get-GitHubFile $script:Token $script:Branch $BLOCKTYPE_API_PATH
        $script:FileSha = $result.Sha
        $parsed = Parse-BlockTypes $result.Content
        $script:Blocks.Clear()
        foreach ($b in $parsed) { $null = $script:Blocks.Add($b) }
        Refresh-ListView
        Log "Fetched $($script:Blocks.Count) block(s) from GitHub (branch: $($script:Branch))." 'ok'
    } catch {
        Log "Fetch failed: $_" 'error'
    } finally {
        $btnFetch.Enabled = $true
        $btnFetch.Text    = 'Fetch from GitHub'
    }
})

# Commit to GitHub
$btnCommit.Add_Click({
    if (-not $script:Token) {
        Log 'No GitHub token configured. Go to the Settings tab to add one.' 'error'
        $tabs.SelectedTab = $tabSettings
        return
    }
    if ($script:Blocks.Count -eq 0) {
        Log 'No blocks loaded — fetch from GitHub first.' 'warn'
        return
    }
    if (-not $script:FileSha) {
        Log 'File SHA not known — please fetch from GitHub before committing.' 'warn'
        return
    }

    # Build commit message
    $msg = [System.Windows.Forms.MessageBox]::Show(
        "Commit changes to '$BLOCKTYPE_API_PATH' on branch '$($script:Branch)'?",
        'Confirm Commit',
        [System.Windows.Forms.MessageBoxButtons]::YesNo,
        [System.Windows.Forms.MessageBoxIcon]::Question)
    if ($msg -ne 'Yes') { return }

    $btnCommit.Enabled = $false
    $btnCommit.Text    = 'Committing...'
    $form.Refresh()

    try {
        # Re-fetch the latest to get the current SHA (avoid conflicts)
        Log 'Refreshing file SHA before commit...'
        $latest = Get-GitHubFile $script:Token $script:Branch $BLOCKTYPE_API_PATH
        $script:FileSha = $latest.Sha

        $newContent = Rebuild-JavaFile $latest.Content $script:Blocks

        $commitMessage = "devtool: update block definitions ($($script:Blocks.Count) blocks)"
        Commit-GitHubFile $script:Token $script:Branch $BLOCKTYPE_API_PATH `
                          $newContent $script:FileSha $commitMessage

        Log "Committed successfully to branch '$($script:Branch)'." 'ok'
        Log "Message: $commitMessage" 'ok'
        $lblEditStatus.ForeColor = $C_GREEN
        $lblEditStatus.Text      = 'Committed!'
    } catch {
        Log "Commit failed: $_" 'error'
    } finally {
        $btnCommit.Enabled = $true
        $btnCommit.Text    = 'Commit to GitHub'
    }
})

# ── Startup: self-update check (background) ───────────────────────────────────
$form.Add_Shown({
    # Run update check in a background job so the UI stays responsive
    $script:sync = [hashtable]::Synchronized(@{ Restart = $false; Done = $false; Msg = '' })
    $captToken  = $script:Token
    $script:rs = [System.Management.Automation.Runspaces.RunspaceFactory]::CreateRunspace()
    $script:rs.Open()
    $script:rs.SessionStateProxy.SetVariable('sync',           $script:sync)
    $script:rs.SessionStateProxy.SetVariable('REPO',           $REPO)
    $script:rs.SessionStateProxy.SetVariable('RELEASE_TAG',    $RELEASE_TAG)
    $script:rs.SessionStateProxy.SetVariable('DEVTOOL_VER_ASSET', $DEVTOOL_VER_ASSET)
    $script:rs.SessionStateProxy.SetVariable('DEVTOOL_PS1_ASSET', $DEVTOOL_PS1_ASSET)
    $script:rs.SessionStateProxy.SetVariable('LocalVerFile',   $LocalVerFile)
    $script:rs.SessionStateProxy.SetVariable('SelfPs1Path',    $SelfPs1Path)
    $script:rs.SessionStateProxy.SetVariable('captToken',      $captToken)

    $script:ps = [System.Management.Automation.PowerShell]::Create()
    $script:ps.Runspace = $script:rs
    $null = $script:ps.AddScript({
        function Get-AuthHdrs([string]$t) {
            $h = @{ 'User-Agent' = 'BlockGame-DevTool'; 'Accept' = 'application/vnd.github+json' }
            if ($t) { $h['Authorization'] = "Bearer $t" }
            return $h
        }
        try {
            $hdrs    = Get-AuthHdrs $captToken
            $release = Invoke-RestMethod `
                           -Uri "https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG" `
                           -Headers $hdrs -TimeoutSec 15
            $verAsset = $release.assets | Where-Object name -eq $DEVTOOL_VER_ASSET |
                            Select-Object -First 1
            if ($verAsset) {
                $remoteVer = (Invoke-RestMethod -Uri $verAsset.browser_download_url `
                                  -Headers $hdrs -TimeoutSec 10).ToString().Trim()
                $localVer  = if (Test-Path $LocalVerFile) {
                                 (Get-Content $LocalVerFile -Raw).Trim()
                             } else { '' }
                if ($remoteVer -ne $localVer) {
                    $ps1Asset = $release.assets | Where-Object name -eq $DEVTOOL_PS1_ASSET |
                                    Select-Object -First 1
                    if ($ps1Asset) {
                        $tmp = "$SelfPs1Path.tmp"
                        Invoke-WebRequest -Uri $ps1Asset.browser_download_url -OutFile $tmp `
                            -UseBasicParsing -Headers $hdrs
                        Move-Item $tmp $SelfPs1Path -Force
                        Set-Content -Path $LocalVerFile -Value $remoteVer -NoNewline
                        Start-Process powershell.exe `
                            -ArgumentList "-NoProfile -ExecutionPolicy Bypass -STA -File `"$SelfPs1Path`""
                        $sync.Restart = $true
                    }
                }
            }
        } catch { }
        $sync.Done = $true
    })

    $script:bgHandle = $script:ps.BeginInvoke()

    $script:updateTimer          = New-Object System.Windows.Forms.Timer
    $script:updateTimer.Interval = 200
    $script:updateTimer.Add_Tick({
        if (-not $script:sync.Done) { return }
        $script:updateTimer.Stop()
        $script:ps.EndInvoke($script:bgHandle) | Out-Null
        $script:ps.Dispose()
        $script:rs.Close()
        $script:rs.Dispose()
        if ($script:sync.Restart) { $form.Close() }
    })
    $script:updateTimer.Start()
})

# ── Run ────────────────────────────────────────────────────────────────────────
[System.Windows.Forms.Application]::Run($form)
