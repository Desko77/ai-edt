<#
.SYNOPSIS
  Self-update the EDT MCP plugin into ONE running 1C:EDT session (the dev/test
  workspace), without touching any other EDT session on the same installation.

.DESCRIPTION
  Steps:
    1. Find the single running 1cedt.exe whose -data workspace matches -WorkspaceMatch.
       With no -WorkspaceMatch every running session qualifies, which resolves cleanly
       when exactly one is open; pass a substring of the workspace path to pick one out
       of several. Capture its exe path and full argument tail for a faithful relaunch.
       Every other 1cedt.exe is left strictly alone.
    2. Gracefully close ONLY that process. Preferred path: the MCP server's own
       restart_edt tool (a PROGRAMMATIC workbench close - no "Exit 1C:EDT?" prompt,
       which is what stalls an unattended window close); falls back to CloseMainWindow
       for older plugins without the tool. NEVER force-kill: if it does not exit within
       -CloseTimeoutSec (e.g. a dialog is up), the script aborts and leaves everything
       as-is for you to resolve.
    3. Run the Equinox p2 director (1cedtc.exe) to install/update the feature from the
       local build repository into the shared p2 profile/bundle-pool. No elevation - the
       EDT install lives under %LOCALAPPDATA% and the pool under %USERPROFILE%\.p2.
    4. Relaunch the dev session with the captured arguments (unless -NoRestart).
    5. Poll the MCP /health endpoint, on the port discovered from the dev workspace's
       prefs (mcpServerPort), until the server is back (or -HealthTimeoutSec).

.NOTES
  Other EDT sessions are never closed, killed or launched. The director updates the
  on-disk profile only; another running session keeps its in-memory plugin until it is
  itself restarted by you. p2 director generally succeeds while another session of the
  same install is open (that session does not hold an exclusive profile lock unless it
  is mid p2-operation); if it does fail on a lock, this script reports it and relaunches
  the dev session with the existing (un-updated) plugin so you are never left without an IDE.

  PowerShell 5.1+ compatible (no PS7-only syntax) so it runs from either shell.

.EXAMPLE
  pwsh -NoProfile -File scripts\edt-selfupdate.ps1
#>
[CmdletBinding()]
param(
    # Substring identifying the target workspace in the 1cedt.exe -data argument.
    # Empty matches any workspace, which is enough when a single session is running.
    [string]$WorkspaceMatch = '',
    # P2 repository to install from. Defaults to the local Maven/Tycho build output.
    [string]$RepoPath,
    # Feature installable-unit id to install/update.
    [string]$FeatureIU = 'ru.aiedt.mcp.server.feature.feature.group',
    # Max seconds to wait for the graceful close before aborting (never kills).
    [int]$CloseTimeoutSec = 120,
    # Max seconds to wait for the MCP /health endpoint after relaunch.
    [int]$HealthTimeoutSec = 240,
    # MCP server port. 0 = auto-discover from the dev workspace's plugin prefs
    # (mcpServerPort); falls back to 12250 when the pref is absent. The server binds
    # the CONFIGURED port (often not 12250), so a hardcoded probe port can miss it.
    [int]$McpPort = 0,
    # Skip the relaunch (leave the dev session closed).
    [switch]$NoRestart,
    # First install of a NEW feature id (not yet in the p2 profile): omit -uninstallIU so
    # the director does not hard-fail trying to remove an IU that is not installed.
    [switch]$SkipUninstall
)

$ErrorActionPreference = 'Stop'

function Write-Step($m) { Write-Host "[selfupdate] $m" -ForegroundColor Cyan }
function Write-Note($m) { Write-Host "[selfupdate] $m" }
function Write-Warn2($m) { Write-Host "[selfupdate] WARN: $m" -ForegroundColor Yellow }
function Write-Err2($m) { Write-Host "[selfupdate] ERROR: $m" -ForegroundColor Red }

# ---------------------------------------------------------------------------
# 0. Resolve and validate the local P2 repository
# ---------------------------------------------------------------------------
if (-not $RepoPath) {
    $RepoPath = Join-Path $PSScriptRoot '..\mcp\repositories\ru.aiedt.mcp.server.repository\target\repository'
}
try {
    $RepoPath = (Resolve-Path -LiteralPath $RepoPath).Path
} catch {
    Write-Err2 "Repo path not found: $RepoPath (build the plugin first)"
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $RepoPath 'content.jar'))) {
    Write-Err2 "Not a P2 repository (no content.jar): $RepoPath"
    exit 2
}
$repoUrl = ([System.Uri]$RepoPath).AbsoluteUri   # file:///E:/.../repository
$builtJar = Get-ChildItem -LiteralPath (Join-Path $RepoPath 'plugins') -Filter 'ru.aiedt.mcp.server_*.jar' -ErrorAction SilentlyContinue |
    Sort-Object Name | Select-Object -Last 1
Write-Step "Repo: $RepoPath"
if ($builtJar) { Write-Note "Built bundle: $($builtJar.Name)" }

# ---------------------------------------------------------------------------
# 1. Locate the single target 1cedt.exe (dev workspace), capture relaunch info
# ---------------------------------------------------------------------------
$allEdt = @(Get-CimInstance Win32_Process -Filter "Name='1cedt.exe'" -ErrorAction SilentlyContinue)
# Match the -data argument VALUE only (quoted or unquoted), not the whole command
# line, so a token that happens to appear in another arg (e.g. a -vm path) can never
# select the wrong session.
$targets = @($allEdt | Where-Object {
    $cl = $_.CommandLine
    if (-not $cl) { return $false }
    $m = [regex]::Match($cl, '-data\s+("([^"]*)"|(\S+))')
    if (-not $m.Success) { return $false }
    $dataVal = if ($m.Groups[2].Success) { $m.Groups[2].Value } else { $m.Groups[3].Value }
    $dataVal -like "*$WorkspaceMatch*"
})

if ($targets.Count -eq 0) {
    $what = if ($WorkspaceMatch) { "whose -data matches '$WorkspaceMatch'" } else { "with a -data workspace" }
    Write-Err2 "No running 1cedt.exe $what. Open the target workspace first."
    if ($allEdt.Count -gt 0) {
        Write-Note "Running EDT sessions (left untouched):"
        $allEdt | ForEach-Object { Write-Note "  PID $($_.ProcessId): $($_.CommandLine)" }
    }
    exit 3
}
if ($targets.Count -gt 1) {
    Write-Err2 "Ambiguous: $($targets.Count) running sessions qualify. Pass -WorkspaceMatch <substring> to pick one."
    $targets | ForEach-Object { Write-Note "  PID $($_.ProcessId): $($_.CommandLine)" }
    exit 3
}

$target = $targets[0]
$targetPid = [int]$target.ProcessId
$cmdLine = $target.CommandLine
$exePath = (Get-Process -Id $targetPid).Path
if (-not $exePath) { $exePath = $target.ExecutablePath }

# argument tail = command line minus the leading (quoted) exe token
if ($cmdLine.StartsWith('"')) {
    $argTail = $cmdLine.Substring($cmdLine.IndexOf('"', 1) + 1).TrimStart()
} else {
    $sp = $cmdLine.IndexOf(' ')
    $argTail = if ($sp -ge 0) { $cmdLine.Substring($sp + 1).TrimStart() } else { '' }
}

$installDir = Split-Path -Parent $exePath
$cedtc = Join-Path $installDir '1cedtc.exe'      # console launcher for headless director
if (-not (Test-Path -LiteralPath $cedtc)) { $cedtc = $exePath }   # fall back to the GUI exe

# derive a console java.exe from the captured -vm (javaw.exe -> java.exe)
$javaExe = $null
$mVm = [regex]::Match($cmdLine, '-vm\s+"?([^"]+?java[w]?\.exe)"?')
if ($mVm.Success) {
    $cand = $mVm.Groups[1].Value -replace 'javaw\.exe$', 'java.exe'
    if (Test-Path -LiteralPath $cand) { $javaExe = $cand }
}

# Discover the configured MCP port from the target workspace's plugin prefs - the
# server binds the port set there, which need not be the 12250 default.
$mWs = [regex]::Match($cmdLine, '-data\s+("([^"]*)"|(\S+))')
$workspaceDir = if ($mWs.Groups[2].Success) { $mWs.Groups[2].Value } elseif ($mWs.Groups[3].Success) { $mWs.Groups[3].Value } else { $null }
if ($McpPort -le 0) {
    $McpPort = 12250
    if ($workspaceDir) {
        $prefs = Join-Path $workspaceDir '.metadata\.plugins\org.eclipse.core.runtime\.settings\ru.aiedt.mcp.server.prefs'
        if (Test-Path -LiteralPath $prefs) {
            $pl = Select-String -LiteralPath $prefs -Pattern '^mcpServerPort=(\d+)' | Select-Object -First 1
            if ($pl) { $McpPort = [int]$pl.Matches[0].Groups[1].Value.Trim() }   # .Trim(): prefs are CRLF
            else { Write-Warn2 "mcpServerPort not in prefs - using default 12250 (override with -McpPort)." }
        } else {
            Write-Warn2 "Workspace prefs not found ($prefs) - using default 12250 (override with -McpPort)."
        }
    }
}

Write-Step "Target session PID $targetPid"
Write-Note "  exe : $exePath"
Write-Note "  args: $argTail"
Write-Note "  director launcher: $cedtc"
if ($javaExe) { Write-Note "  director VM: $javaExe" }
Write-Note "  MCP port: $McpPort"

# ---------------------------------------------------------------------------
# 2. Graceful close ONLY this session (never force-kill)
# ---------------------------------------------------------------------------
# Preferred: ask the MCP server's own restart_edt tool to shut down. That is a
# PROGRAMMATIC workbench close - it does NOT raise the "Exit 1C:EDT?" confirmation
# that a window close (CloseMainWindow) does, which is what stalls an unattended
# run. Fall back to CloseMainWindow for older plugins without the tool. The body
# goes through a temp file so curl, not PowerShell, owns the JSON quoting.
Write-Step "Closing dev session gracefully (no force-kill)..."
$proc = Get-Process -Id $targetPid
$closed = $false

$curlExe = Join-Path $env:SystemRoot 'System32\curl.exe'
if (Test-Path -LiteralPath $curlExe) {
    $bodyFile = Join-Path $env:TEMP ('restart_edt_' + [System.Guid]::NewGuid().ToString('N') + '.json')
    Set-Content -LiteralPath $bodyFile -Encoding ascii -Value '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"restart_edt","arguments":{"action":"shutdown","delayMs":500}}}'
    Write-Note "Requesting programmatic shutdown via restart_edt (port $McpPort)..."
    $shutResp = & $curlExe -sS -m 15 -X POST "http://localhost:$McpPort/mcp" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' --data-binary "@$bodyFile" 2>&1
    try { [System.IO.File]::Delete($bodyFile) } catch { }
    if ("$shutResp" -match 'shutdown|"success"|EDT will') {
        Write-Note "  restart_edt accepted; waiting for exit..."
        if ($proc.WaitForExit($CloseTimeoutSec * 1000)) { $closed = $true }
    } else {
        Write-Note "  restart_edt unavailable/unconfirmed (older plugin or server down): $shutResp"
    }
} else {
    Write-Note "curl.exe not found - using window close."
}

if (-not $closed) {
    Write-Note "Falling back to window close (CloseMainWindow)..."
    try {
        $null = $proc.CloseMainWindow()
        if ($proc.WaitForExit($CloseTimeoutSec * 1000)) { $closed = $true }
    } catch {
        # The process may already be exiting/gone (e.g. restart_edt closed it but curl
        # timed out reading the SSE response). CloseMainWindow throws on an exited
        # process - that means it is no longer running, so treat it as closed.
        $closed = $true
    }
}

if (-not $closed) {
    Write-Err2 ("Session did not close within ${CloseTimeoutSec}s (an EDT exit-confirmation or " +
        "Save dialog may be open). Aborting WITHOUT killing. Resolve it in EDT, then re-run.")
    exit 4
}
Write-Note "Closed. Waiting for the OS to release file handles..."
Start-Sleep -Seconds 3

# ---------------------------------------------------------------------------
# 3. p2 director: install/update the feature from the local repo
# ---------------------------------------------------------------------------
# The feature is a p2 singleton - a plain -installIU of a new version conflicts with the
# already-installed one ("only one can be installed"). Uninstall + install the same IU in
# ONE director request = an atomic UPDATE (remove the old version, add the highest from the
# repo). This assumes a prior version is installed, which is the self-update premise.
$dirArgs = @(
    '-nosplash',
    '-application', 'org.eclipse.equinox.p2.director',
    '-repository', $repoUrl
)
if (-not $SkipUninstall) { $dirArgs += @('-uninstallIU', $FeatureIU) }
$dirArgs += @(
    '-installIU', $FeatureIU,
    '-profileProperties', 'org.eclipse.update.reconcile=true'
)
# -vm must precede the application/other launcher args, otherwise the Equinox
# launcher ignores it and the director runs on whatever Java is on PATH.
if ($javaExe) { $dirArgs = @('-vm', $javaExe) + $dirArgs }

Write-Step "Running p2 director..."
Write-Note ("  {0} {1}" -f $cedtc, ($dirArgs -join ' '))
$dirOut = & $cedtc @dirArgs 2>&1
$dirExit = $LASTEXITCODE
$dirOut | ForEach-Object { Write-Note "  | $_" }

$updateOk = ($dirExit -eq 0)
if ($updateOk) {
    Write-Step "Director OK (exit 0) - feature installed/updated."
} else {
    Write-Warn2 ("Director FAILED (exit $dirExit). The on-disk plugin is unchanged. " +
        "If this is a profile lock, another EDT session of this installation may be holding it. " +
        "Relaunching the dev session with the existing plugin so you are not left without an IDE.")
}

# ---------------------------------------------------------------------------
# 4. Relaunch the dev session faithfully (verbatim argument tail)
# ---------------------------------------------------------------------------
if ($NoRestart) {
    Write-Step "Relaunch skipped (-NoRestart)."
    if ($updateOk) { exit 0 } else { exit 5 }
}
Write-Step "Relaunching dev session..."
# .NET Process.Start(file, args) forwards the argument string verbatim (no PS re-quoting).
try {
    $started = [System.Diagnostics.Process]::Start($exePath, $argTail)
    Write-Note "Started PID $($started.Id)."
} catch {
    Write-Err2 "Failed to relaunch EDT ($exePath): $_"
    exit 7
}

# ---------------------------------------------------------------------------
# 5. Wait for the MCP server to come back
# ---------------------------------------------------------------------------
Write-Step "Waiting for MCP /health on port $McpPort (up to ${HealthTimeoutSec}s)..."
$healthUrl = "http://localhost:$McpPort/health"
$deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
$healthy = $false
while ((Get-Date) -lt $deadline) {
    try {
        $resp = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
        if ($resp.StatusCode -eq 200) { $healthy = $true; break }
    } catch {
        # Any HTTP response (even 401 when bearer auth is enabled) means the listener
        # is alive - treat that as up. Only a connection failure keeps us waiting.
        if ($_.Exception.Response) { $healthy = $true; break }
    }
    Start-Sleep -Seconds 3
}
if ($healthy) {
    # The listener is up; now wait for the workspace to finish indexing so a caller does not race the
    # initial build. Bounded by the same timeout - a stuck build must not hang the self-update forever.
    $phaseDeadline = (Get-Date).AddSeconds($HealthTimeoutSec)
    $phase = "unknown"
    while ((Get-Date) -lt $phaseDeadline) {
        try {
            $r = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 5
            $phase = ($r.Content | ConvertFrom-Json).phase
            if ($phase -eq "ready") { break }
        } catch { }
        Start-Sleep -Seconds 3
    }
    if ($phase -eq "ready") {
        Write-Step "MCP server is UP and workspace is ready. Self-update complete."
    } else {
        Write-Warn2 "MCP server is UP but workspace phase is '$phase' after ${HealthTimeoutSec}s; proceeding."
    }
    if ($builtJar) { Write-Note "Loaded build target: $($builtJar.Name)" }
    if ($updateOk) { exit 0 } else { exit 5 }
} else {
    Write-Warn2 "MCP /health did not respond within ${HealthTimeoutSec}s. EDT may still be starting; check manually."
    exit 6
}
