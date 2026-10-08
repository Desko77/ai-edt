<#
.SYNOPSIS
  Self-update the EDT MCP plugin into ONE running 1C:EDT session (the dev/test
  workspace), without touching any other EDT session on the same installation. With
  -AllInstallations, install into EVERY installation that carries the plugin while no
  EDT of those installations is running - the release-checklist step that makes all
  installations on the machine hold the same build.

.DESCRIPTION
  One running session (default mode). Steps:
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
       chosen repository - the local build or the one published with a release - into
       the shared p2 profile/bundle-pool. Installing something older than what is
       already running is refused unless -AllowDowngrade. No elevation - the
       EDT install lives under %LOCALAPPDATA% and the pool under %USERPROFILE%\.p2.
    4. Relaunch the dev session with the captured arguments (unless -NoRestart).
    5. Poll the MCP /health endpoint, on the port discovered from the dev workspace's
       prefs (mcpServerPort), until the server is back (or -HealthTimeoutSec).

  -AllInstallations (closed-EDT mode). Steps:
    1. Ask scripts/report-plugin-jars.py --json which installations record the plugin and
       where each of them reads it from - its own configuration area or a shared profile
       in the user's home. One place decides what an installation is, so the installer
       and the jar report never disagree.
    2. Read the version each installation holds now, the highest among its records: a
       bundle recorded in a shared profile is the one that wins over the install's file.
    3. Refuse the whole run while any 1cedt.exe or javaw.exe of a target installation is
       alive, naming the PIDs. No process is closed or killed in this mode, and no
       session is started: close them yourself and re-run.
    4. Run the p2 director of EACH target installation, with the -vm from that
       installation's 1cedt.ini.
    5. Read the versions back and print installation / before / after, exiting non-zero
       unless every installation carries the installed version.

  -WhatIf prints that plan without downloading, installing or otherwise changing
  anything.

.NOTES
  Other EDT sessions are never closed, killed or launched. The director updates the
  on-disk profile only; another running session keeps its in-memory plugin until it is
  itself restarted by you. p2 director generally succeeds while another session of the
  same install is open (that session does not hold an exclusive profile lock unless it
  is mid p2-operation); if it does fail on a lock, this script reports it and relaunches
  the dev session with the existing (un-updated) plugin so you are never left without an IDE.

  Exit codes: 0 done; 2 repository or argument problem; 3 refused (an EDT of a target
  installation is running, or a downgrade without -AllowDowngrade); 4 the session did not
  close; 5 the director failed; 6 the version could not be confirmed afterwards; 7 the
  session could not be relaunched.

  PowerShell 5.1+ compatible (no PS7-only syntax) so it runs from either shell.

.EXAMPLE
  pwsh -NoProfile -File scripts\edt-selfupdate.ps1
  Local build when this working copy has one, otherwise the latest release.

.EXAMPLE
  pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -WorkspaceMatch SSL_Demo -Source release
  The published build, whatever is in target\. Add -ReleaseTag v0.2.1 to pin a version.

.EXAMPLE
  pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -Source local
  The build in this working copy, and an error rather than a download if there is none.

.EXAMPLE
  pwsh -NoProfile -File scripts\edt-selfupdate.ps1 -AllInstallations -WhatIf -RepoPath .\mcp\repositories\ru.aiedt.mcp.server.repository\target\repository
  Which installations would take this build, and which are held back by a running EDT.
#>
[CmdletBinding()]
param(
    # Substring identifying the target workspace in the 1cedt.exe -data argument.
    # Empty matches any workspace, which is enough when a single session is running.
    [string]$WorkspaceMatch = '',
    # P2 repository to install from. Given explicitly it wins over -Source.
    [string]$RepoPath,
    # Where the plugin comes from when -RepoPath is not given:
    #   local   - the Maven/Tycho build output in this working copy
    #   release - the p2 repository published with a GitHub release
    #   auto    - the local build when it exists, the release otherwise
    [ValidateSet('auto', 'local', 'release')]
    [string]$Source = 'auto',
    # Which release to take with -Source release: 'latest' or a tag such as v0.2.1.
    [string]$ReleaseTag = 'latest',
    # Repository the release is downloaded from.
    [string]$Repo = 'Desko77/ai-edt',
    # Install a bundle older than the one already running. Off by default, because
    # a local build is 0.1.0.<timestamp> and would otherwise quietly replace a release.
    [switch]$AllowDowngrade,
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
    [switch]$SkipUninstall,
    # Closed-EDT mode: install into EVERY installation that carries the plugin, one
    # director run each, instead of into one running session. Refuses while an EDT of a
    # target installation is running - no session is closed, killed or started.
    [switch]$AllInstallations,
    # Print the plan of -AllInstallations and change nothing.
    [Alias('DryRun')]
    [switch]$WhatIf,
    # Python interpreter that reads the installation report (scripts/report-plugin-jars.py).
    # Empty searches python and python3 on PATH.
    [string]$PythonExe = '',
    # java.exe for the directors of installations whose 1cedt.ini names no -vm (the ones the 1C
    # launcher starts). Empty takes JAVA_HOME, and with that empty too the director uses the java
    # on PATH.
    [string]$JavaExe = '',
    # Bundle whose installations and versions are read. The feature installed is -FeatureIU.
    [string]$BundleSymbolicName = 'ru.aiedt.mcp.server'
)

$ErrorActionPreference = 'Stop'

function Write-Step($m) { Write-Host "[selfupdate] $m" -ForegroundColor Cyan }
function Write-Note($m) { Write-Host "[selfupdate] $m" }
function Write-Warn2($m) { Write-Host "[selfupdate] WARN: $m" -ForegroundColor Yellow }
function Write-Err2($m) { Write-Host "[selfupdate] ERROR: $m" -ForegroundColor Red }

if ($WhatIf -and -not $AllInstallations) {
    Write-Err2 "-WhatIf belongs to -AllInstallations: the single-session mode changes a running session and has no dry run."
    exit 2
}

# ---------------------------------------------------------------------------
# 0. Resolve and validate the P2 repository (local build or published release)
# ---------------------------------------------------------------------------

# Compares two bundle versions. A local build is 0.1.0.<timestamp>, a release is
# 0.2.1 - so the numeric part decides first and the qualifier only breaks ties
# between builds of the same version. [version] cannot be used: the timestamp
# qualifier overflows Int32.
function Compare-BundleVersion([string]$a, [string]$b) {
    $pa = $a -split '\.'; $pb = $b -split '\.'
    for ($i = 0; $i -lt 3; $i++) {
        $na = 0; $nb = 0
        [void][int]::TryParse($pa[$i], [ref]$na)
        [void][int]::TryParse($pb[$i], [ref]$nb)
        if ($na -ne $nb) { if ($na -gt $nb) { return 1 } else { return -1 } }
    }
    $qa = if ($pa.Count -gt 3) { $pa[3] } else { '' }
    $qb = if ($pb.Count -gt 3) { $pb[3] } else { '' }
    # One side without a qualifier is not an older build - it is a version reported
    # without one. The server does exactly that (major.minor.micro), so comparing a
    # local 0.1.0.<timestamp> against it by qualifier would call every local build
    # newer, and a release against itself older. Qualifiers only separate builds of
    # the same version when both carry one.
    if (-not $qa -or -not $qb) { return 0 }
    return [string]::Compare($qa, $qb, [StringComparison]::Ordinal)
}

# Every call this script makes to its own server goes through here. Deliberately
# not Invoke-WebRequest / Invoke-RestMethod: those honour the system proxy, and a
# proxy that cannot reach localhost turns a healthy server into a generic
# HttpRequestException. That once disabled the downgrade check silently, and the
# same trap sits under the /health poll - so both use this.
#
# Returns Answered (the listener replied at all, whatever the status) and Body.
function Invoke-LocalHttp([string]$url) {
    $result = @{ Answered = $false; Body = $null }
    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Proxy = $null
        $req.Timeout = 5000
        $resp = $req.GetResponse()
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $result.Body = $reader.ReadToEnd()
        $reader.Close(); $resp.Close()
        $result.Answered = $true
    } catch [System.Net.WebException] {
        # A status code means the listener is there; only a connection failure does not.
        if ($_.Exception.Response) { $result.Answered = $true }
    } catch {
        # Anything else counts as no answer.
    }
    return $result
}

# Asks the running server what plugin version it is.
function Get-RunningPluginVersion([int]$port) {
    $r = Invoke-LocalHttp "http://localhost:$port/mcp"
    if ($r.Body) {
        $m = [regex]::Match($r.Body, '"version"\s*:\s*"([^"]+)"')
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return $null
}

# What the installation currently provisions, for when the server does not answer.
#
# Read from bundles.info - the list the configurator actually loads - and not from
# the bundle pool. The pool keeps every version ever downloaded, including the
# 3.1.0.* line this product carried before the renumbering, so its maximum is a
# historical artefact: taking it would refuse every install rather than only a
# downgrade. The install's own plugins directory is the fallback, and it holds
# what was provisioned rather than an archive.
function Get-InstalledPluginVersion([string]$installDir) {
    $cfg = Join-Path $installDir 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
    if (Test-Path -LiteralPath $cfg) {
        foreach ($line in (Get-Content -LiteralPath $cfg)) {
            $m = [regex]::Match($line, '^ru\.aiedt\.mcp\.server,([^,]+),')
            if ($m.Success) { return $m.Groups[1].Value }
        }
    }
    $best = $null
    $dir = Join-Path $installDir 'plugins'
    if (Test-Path -LiteralPath $dir) {
        foreach ($f in (Get-ChildItem -LiteralPath $dir -Filter 'ru.aiedt.mcp.server_*.jar' -ErrorAction SilentlyContinue)) {
            $m = [regex]::Match($f.Name, '^ru\.aiedt\.mcp\.server_(.+)\.jar$')
            if ($m.Success -and (-not $best -or (Compare-BundleVersion $m.Groups[1].Value $best) -gt 0)) {
                $best = $m.Groups[1].Value
            }
        }
    }
    return $best
}

# Validates a p2 repository and picks the bundle it offers. One place, so the
# release picked up as a fallback goes through exactly the checks the first
# repository did - an unvalidated second path is how a fallback quietly becomes
# the unchecked one.
#
# Highest by version, not by name: sorting strings puts 0.9 above 0.10 and orders
# timestamp qualifiers by text.
function Resolve-RepoBundle([string]$repoPath) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoPath 'content.jar'))) {
        Write-Err2 "Not a P2 repository (no content.jar): $repoPath"
        exit 2
    }
    $jar = $null
    $version = $null
    foreach ($cand in (Get-ChildItem -LiteralPath (Join-Path $repoPath 'plugins') -Filter 'ru.aiedt.mcp.server_*.jar' -ErrorAction SilentlyContinue)) {
        $mc = [regex]::Match($cand.Name, '^ru\.aiedt\.mcp\.server_(.+)\.jar$')
        if (-not $mc.Success) { continue }
        if (-not $jar -or (Compare-BundleVersion $mc.Groups[1].Value $version) -gt 0) {
            $jar = $cand
            $version = $mc.Groups[1].Value
        }
    }
    # PSCustomObject rather than a hashtable: both return intact from a function,
    # but this one leaves no room to wonder how an older host treats it, and the
    # 5.1 this script promises to run on cannot be exercised from here.
    return [PSCustomObject]@{ Jar = $jar; Version = $version }
}

# Downloads the p2 repository published with a release and unpacks it. The asset
# name is fixed by release.yml, so 'latest' keeps working without a tag.
function Get-ReleaseRepo([string]$repo, [string]$tag) {
    $asset = 'AI-EDT-update-site.zip'
    $url = if ($tag -and $tag -ne 'latest') {
        "https://github.com/$repo/releases/download/$tag/$asset"
    } else {
        "https://github.com/$repo/releases/latest/download/$asset"
    }
    $dest = Join-Path $env:TEMP ("aiedt-selfupdate-" + $tag)
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    $zip = Join-Path $dest $asset
    Write-Step "Downloading $tag from $repo..."
    Write-Note "  $url"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    } catch {
        Write-Err2 "Download failed: $($_.Exception.Message)"
        Write-Err2 "No network, or the release carries no $asset. Build locally and pass -Source local."
        exit 2
    }
    Expand-Archive -LiteralPath $zip -DestinationPath $dest -Force
    Remove-Item -LiteralPath $zip -Force
    return $dest
}

$localRepo = Join-Path $PSScriptRoot '..\mcp\repositories\ru.aiedt.mcp.server.repository\target\repository'
$hasLocal = Test-Path -LiteralPath (Join-Path $localRepo 'content.jar')

if ($RepoPath) {
    # An explicit path wins over -Source: the caller already said where to look.
    $sourceLabel = 'explicit path'
} elseif ($Source -eq 'release') {
    # A dry run promises to change nothing, and a download writes to the temp directory.
    if ($WhatIf) {
        Write-Err2 "-WhatIf does not download: pass -RepoPath <directory of a built repository>."
        exit 2
    }
    $RepoPath = Get-ReleaseRepo $Repo $ReleaseTag
    $sourceLabel = "release $ReleaseTag"
} elseif ($Source -eq 'local') {
    if (-not $hasLocal) {
        Write-Err2 "No local build at $localRepo - run the Maven build, or use -Source release."
        exit 2
    }
    $RepoPath = $localRepo
    $sourceLabel = 'local build'
} else {
    # auto: whatever is at hand. A local build means someone is iterating on the
    # code and wants that; without one, the published release is the only sane
    # answer - a fresh clone has nothing to install.
    if ($hasLocal) {
        $RepoPath = $localRepo
        $sourceLabel = 'local build (auto)'
    } elseif ($WhatIf) {
        Write-Err2 "-WhatIf does not download: pass -RepoPath <directory of a built repository>."
        exit 2
    } else {
        $RepoPath = Get-ReleaseRepo $Repo $ReleaseTag
        $sourceLabel = "release $ReleaseTag (auto, no local build)"
    }
}

try {
    $RepoPath = (Resolve-Path -LiteralPath $RepoPath).Path
} catch {
    Write-Err2 "Repo path not found: $RepoPath (build the plugin first)"
    exit 2
}
$repoUrl = ([System.Uri]$RepoPath).AbsoluteUri   # file:///E:/.../repository
$repoInfo = Resolve-RepoBundle $RepoPath
$builtJar = $repoInfo.Jar
Write-Step "Repo: $RepoPath"
Write-Note "  source: $sourceLabel"
$repoVersion = $repoInfo.Version
if ($builtJar) { Write-Note "  bundle: $($builtJar.Name)" }

# ---------------------------------------------------------------------------
# 0b. -AllInstallations: install into every installation that records the plugin
#     while no EDT of those installations is running (closed-EDT mode)
# ---------------------------------------------------------------------------
if ($AllInstallations) {

    # Where an installation is and where it reads its bundles.info from is decided by
    # scripts/report-plugin-jars.py, which answers that question for the jar report too. Reading
    # the layout a second time here is how the installer and the report come to disagree about
    # which version an installation actually loads.
    function Resolve-PythonExe([string]$explicit) {
        $names = if ($explicit) { @($explicit) } else { @('python', 'python3') }
        foreach ($name in $names) {
            $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
            if (-not $command) { continue }
            $path = $command.Source
            # An interpreter that answers nothing is the Microsoft Store stub, which opens a
            # window instead of running a script.
            $probe = & $path -c "print('ok')" 2>&1
            if ($LASTEXITCODE -eq 0 -and ($probe | Out-String).Trim() -eq 'ok') { return $path }
        }
        return $null
    }

    # The installation report as objects, or $null when the interpreter or the report failed.
    function Get-InstallationReport([string]$interpreter, [string]$reportPath, [string]$bundle) {
        $output = & $interpreter -X utf8 $reportPath --json --bundle $bundle 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Err2 "The installation report failed (exit $LASTEXITCODE):"
            $output | ForEach-Object { Write-Note "  | $_" }
            return $null
        }
        try { return ($output | Out-String | ConvertFrom-Json) }
        catch {
            Write-Err2 "The installation report did not print JSON: $($_.Exception.Message)"
            return $null
        }
    }

    # True when a path is a directory itself or lies under it.
    function Test-UnderPath([string]$path, [string]$directory) {
        if (-not $path -or -not $directory) { return $false }
        $p = $path.TrimEnd('\', '/')
        $d = $directory.TrimEnd('\', '/')
        return $p.StartsWith($d + '\', [StringComparison]::OrdinalIgnoreCase) -or
               $p.StartsWith($d + '/', [StringComparison]::OrdinalIgnoreCase)
    }

    # The processes of an installation: its own launcher, and the JVM that launcher started. The
    # JVM runs from a shared JDK, so its own path says nothing about the installation - the
    # installation directory appears in its command line, in the launcher jar it was started with.
    function Get-InstallationProcess([string]$installDir) {
        $found = @()
        $filter = "Name='1cedt.exe' or Name='1cedtc.exe' or Name='javaw.exe' or Name='java.exe'"
        foreach ($p in @(Get-CimInstance Win32_Process -Filter $filter -ErrorAction SilentlyContinue)) {
            $command = $p.CommandLine
            $inCommand = $command -and $command.IndexOf($installDir, [StringComparison]::OrdinalIgnoreCase) -ge 0
            if ((Test-UnderPath $p.ExecutablePath $installDir) -or $inCommand) { $found += $p }
        }
        return $found
    }

    # The java.exe an installation's own launcher would use. The director runs without a session,
    # so there is no command line to take one from: the ini of the installation names it, and an
    # installation the 1C launcher starts has none written down - for those the caller's -JavaExe
    # or JAVA_HOME answers, and with neither the director falls back to the java on PATH.
    function Get-InstallJavaExe([string]$installDir) {
        $ini = Join-Path $installDir '1cedt.ini'
        if (Test-Path -LiteralPath $ini) {
            $lines = @(Get-Content -LiteralPath $ini)
            for ($i = 0; $i -lt $lines.Count - 1; $i++) {
                if ($lines[$i].Trim() -ne '-vm') { continue }
                $value = $lines[$i + 1].Trim()
                $console = $value -replace 'javaw\.exe$', 'java.exe'
                if (Test-Path -LiteralPath $console) { return $console }
                if (Test-Path -LiteralPath $value) { return $value }
                break
            }
        }
        if ($JavaExe) { return $JavaExe }
        if ($env:JAVA_HOME) {
            $fromHome = Join-Path $env:JAVA_HOME 'bin\java.exe'
            if (Test-Path -LiteralPath $fromHome) { return $fromHome }
        }
        return $null
    }

    # The version of EDT an installation carries, for the table.
    function Get-InstallProductVersion([string]$installDir) {
        $ini = Join-Path $installDir 'configuration\config.ini'
        if (-not (Test-Path -LiteralPath $ini)) { return $null }
        foreach ($line in (Get-Content -LiteralPath $ini)) {
            $m = [regex]::Match($line, '^(?:product\.version|eclipse\.buildId)=(.+)$')
            if ($m.Success) { return $m.Groups[1].Value.Trim() }
        }
        return $null
    }

    # What an installation loads now: the highest version among its records. A bundle recorded in a
    # shared profile is the one that wins over the same bundle in the installation's own file, so
    # the highest of them is what a session started from this installation would run.
    function Get-RecordedVersion($records) {
        $best = $null
        foreach ($record in @($records)) {
            foreach ($version in @($record.versions)) {
                if (-not $best -or (Compare-BundleVersion $version $best) -gt 0) { $best = $version }
            }
        }
        return $best
    }

    # What the report records for one installation directory.
    function Get-ReportedVersion($report, [string]$installDir) {
        foreach ($entry in @($report.installations)) {
            if ([string]$entry.install -eq $installDir) { return (Get-RecordedVersion $entry.records) }
        }
        return $null
    }

    # One line per installation, the same shape before and after the run.
    function Write-PlanLine($p) {
        Write-Note ("  {0}  {1} -> {2}  {3}  [{4}]" -f $p.Install, $p.Before, $p.After, $p.Edt, $p.Status)
    }

    $reportPath = Join-Path $PSScriptRoot 'report-plugin-jars.py'
    if (-not (Test-Path -LiteralPath $reportPath)) {
        Write-Err2 "Installation report not found: $reportPath"
        exit 2
    }
    $python = Resolve-PythonExe $PythonExe
    if (-not $python) {
        Write-Err2 "No Python interpreter found (tried python and python3). Pass -PythonExe <path>."
        exit 2
    }
    if (-not $repoVersion) {
        Write-Err2 "No $BundleSymbolicName jar in $RepoPath - nothing to install."
        exit 2
    }
    if ($JavaExe -and -not (Test-Path -LiteralPath $JavaExe)) {
        Write-Err2 "-JavaExe not found: $JavaExe"
        exit 2
    }

    Write-Step "Repository: $repoVersion ($sourceLabel)"
    $report = Get-InstallationReport $python $reportPath $BundleSymbolicName
    if (-not $report) { exit 2 }

    $entries = @($report.installations)
    if ($entries.Count -eq 0) {
        Write-Err2 "No installation records $BundleSymbolicName. Nothing to install into."
        foreach ($orphan in @($report.unclaimed)) {
            Write-Note "  recorded but attributed to no installation: $($orphan.bundlesInfo)"
        }
        exit 3
    }

    $plan = @()
    foreach ($entry in $entries) {
        $dir = [string]$entry.install
        $before = Get-RecordedVersion $entry.records
        $running = @(Get-InstallationProcess $dir)
        $status = 'install'
        if ($running.Count -gt 0) {
            $status = 'running'
        } elseif ($before -and (Compare-BundleVersion $repoVersion $before) -lt 0) {
            if ($AllowDowngrade) { $status = 'downgrade-allowed' } else { $status = 'downgrade' }
        } elseif ($before -and (Compare-BundleVersion $repoVersion $before) -eq 0) {
            $status = 'current'
        }
        $plan += [PSCustomObject]@{
            Install = $dir
            Edt     = Get-InstallProductVersion $dir
            Before  = $before
            After   = $before
            Status  = $status
            Records = @($entry.records)
            Running = $running
            Java    = Get-InstallJavaExe $dir
            Launch  = [string]$entry.launcher
        }
    }

    Write-Step "Found $($plan.Count) installation(s) recording $BundleSymbolicName; repository has $repoVersion"
    foreach ($p in $plan) {
        Write-PlanLine $p
        foreach ($record in $p.Records) {
            Write-Note ("    {0} {1} = {2}" -f ($record.kind + ':'), $record.bundlesInfo, (@($record.versions) -join ', '))
        }
    }

    # Each installation's own director: it installs into the profile that installation is
    # configured with, which for a read-only installation is the one in the user's home.
    $actions = @()
    foreach ($p in $plan) {
        if ($p.Status -ne 'install' -and $p.Status -ne 'downgrade-allowed') { continue }
        $launcher = $p.Launch
        if (-not $launcher -or -not (Test-Path -LiteralPath $launcher)) {
            $launcher = Join-Path $p.Install '1cedtc.exe'
            if (-not (Test-Path -LiteralPath $launcher)) { $launcher = Join-Path $p.Install '1cedt.exe' }
        }
        $arguments = @()
        if ($p.Java) {
            $arguments += @('-vm', $p.Java)
        } else {
            Write-Warn2 "No java for $($p.Install): the director will use the one on PATH (-JavaExe <java.exe> says which)."
        }
        $arguments += @('-nosplash', '-application', 'org.eclipse.equinox.p2.director',
                        '-repository', $repoUrl)
        if (-not $SkipUninstall) { $arguments += @('-uninstallIU', $FeatureIU) }
        $arguments += @('-installIU', $FeatureIU, '-profileProperties', 'org.eclipse.update.reconcile=true')
        $actions += [PSCustomObject]@{ Install = $p.Install; Launcher = $launcher; Arguments = $arguments }
    }

    $running = @($plan | Where-Object { $_.Status -eq 'running' })
    $refused = @($plan | Where-Object { $_.Status -eq 'downgrade' })

    if ($WhatIf) {
        Write-Step "-WhatIf: nothing was downloaded, installed or otherwise changed."
        if ($actions.Count -eq 0) {
            Write-Note "  Every installation already carries $repoVersion."
        } else {
            Write-Note "  The director would run:"
            foreach ($a in $actions) {
                Write-Note "    $($a.Launcher)"
                Write-Note "      $($a.Arguments -join ' ')"
            }
        }
    }

    if ($running.Count -gt 0) {
        Write-Err2 "$($running.Count) installation(s) have a running 1C:EDT: close it and re-run. No process is closed or killed here."
        foreach ($p in $running) {
            foreach ($process in $p.Running) {
                Write-Note "  PID $($process.ProcessId) $($process.Name)  [$($p.Install)]"
            }
        }
    }
    if ($refused.Count -gt 0) {
        Write-Err2 "$($refused.Count) installation(s) carry a higher version than $repoVersion. Re-run with -AllowDowngrade to replace it."
        foreach ($p in $refused) { Write-Note "  $($p.Install) is at $($p.Before)" }
    }
    if ($running.Count -gt 0 -or $refused.Count -gt 0) {
        Write-Step "Nothing was installed."
        exit 3
    }
    if ($WhatIf) { exit 0 }
    if ($actions.Count -eq 0) {
        Write-Step "Every installation already carries $repoVersion. Nothing to do."
        exit 0
    }

    $failed = @()
    foreach ($a in $actions) {
        Write-Step "Installing into $($a.Install)"
        $directorArgs = $a.Arguments
        Write-Note "  $($a.Launcher) $($directorArgs -join ' ')"
        $code = 1
        try {
            $output = & $a.Launcher @directorArgs 2>&1
            $code = $LASTEXITCODE
            $output | ForEach-Object { Write-Note "  | $_" }
        } catch {
            Write-Err2 "The director did not run: $($_.Exception.Message)"
        }
        if ($code -ne 0) {
            Write-Err2 "The director failed (exit $code) for $($a.Install)."
            $failed += $a.Install
        }
    }

    Write-Step "Reading the versions back from bundles.info..."
    $after = Get-InstallationReport $python $reportPath $BundleSymbolicName
    if (-not $after) {
        Write-Err2 "The installation report failed after the install, so the versions are not confirmed."
        exit 6
    }
    foreach ($p in $plan) {
        $p.After = Get-ReportedVersion $after $p.Install
        if ($p.Status -ne 'install' -and $p.Status -ne 'downgrade-allowed') { continue }
        if ($p.After -eq $repoVersion) { $p.Status = 'updated' } else { $p.Status = 'NOT CONFIRMED' }
    }

    Write-Step "Installation - version before - version after"
    foreach ($p in $plan) { Write-PlanLine $p }

    $unconfirmed = @($plan | Where-Object { $_.Status -eq 'NOT CONFIRMED' })
    if ($failed.Count -gt 0) {
        Write-Err2 "The director failed for: $($failed -join '; ')"
        exit 5
    }
    if ($unconfirmed.Count -gt 0) {
        Write-Err2 "The version was not confirmed in: $(@($unconfirmed | ForEach-Object { $_.Install }) -join '; ')"
        exit 6
    }
    Write-Step "Every installation that records the plugin carries $repoVersion."
    exit 0
}

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

# A local build always carries 0.1.0.<timestamp>, whatever the released version
# is - the poms keep 0.1.0-SNAPSHOT and only the tag build rewrites it. So
# installing a local build over a release silently moves the session backwards,
# and the only visible sign is a plugin that lost the fixes it just shipped.
# Ask the running server what it is before closing it.
# What is in place now. The server answers with major.minor.micro and the disk
# carries the qualifier too, so the two are not interchangeable: take whichever
# is higher, and on a tie the qualified one, because only that can tell two
# builds of the same version apart.
$fromServer = Get-RunningPluginVersion $McpPort
$fromDisk = Get-InstalledPluginVersion $installDir
$currentVersion = $null
if ($fromServer -and $fromDisk) {
    $c = Compare-BundleVersion $fromDisk $fromServer
    $currentVersion = if ($c -gt 0) { $fromDisk } elseif ($c -lt 0) { $fromServer } else { $fromDisk }
} elseif ($fromServer) {
    $currentVersion = $fromServer
} else {
    $currentVersion = $fromDisk
}
if ($currentVersion) {
    Write-Note ("  installed: {0} (server: {1}, disk: {2})" -f $currentVersion,
        $(if ($fromServer) { $fromServer } else { 'no answer' }),
        $(if ($fromDisk) { $fromDisk } else { 'not found' }))
} else {
    Write-Note "  installed: nothing found - treating this as a first install"
}

# Refuses to move the session backwards, and switches auto to the release when the
# local build it picked turns out to be the older one - a stale target\ directory
# is the normal state after a release, and the default run must not die on it.
# Returns the repository to install from.
function Confirm-NotADowngrade {
    if (-not $script:repoVersion) {
        # content.jar is there but no bundle name parses. Nothing can be compared,
        # and a check that cannot run must not pass silently - that is how the
        # downgrade this exists to stop got through the first time.
        Write-Err2 "Cannot read a plugin version out of $script:RepoPath - refusing to install blind."
        if (-not $AllowDowngrade) {
            Write-Err2 "Pass -AllowDowngrade to install it anyway."
            exit 3
        }
        return
    }
    if (-not $currentVersion) { return }
    if ((Compare-BundleVersion $script:repoVersion $currentVersion) -ge 0) { return }
    if ($AllowDowngrade) {
        Write-Warn2 "$script:repoVersion is older than $currentVersion - proceeding, -AllowDowngrade was given."
        return
    }
    if ($Source -eq 'auto' -and $script:sourceLabel -like 'local build*') {
        Write-Warn2 "The local build $script:repoVersion is older than $currentVersion - switching to the release."
        $script:RepoPath = (Resolve-Path -LiteralPath (Get-ReleaseRepo $Repo $ReleaseTag)).Path
        $script:sourceLabel = "release $ReleaseTag (auto, local build was older)"
        $script:repoUrl = ([System.Uri]$script:RepoPath).AbsoluteUri
        # The release goes through the same validation the first repository did,
        # then through this same check - a fallback nobody re-checks is the one
        # that ships whatever it happens to contain.
        $info = Resolve-RepoBundle $script:RepoPath
        $script:builtJar = $info.Jar
        $script:repoVersion = $info.Version
        Write-Note "  now installing: $script:repoVersion from $script:RepoPath"
        Confirm-NotADowngrade
        return
    }
    Write-Err2 "$script:repoVersion is OLDER than the installed $currentVersion - refusing to downgrade."
    Write-Err2 "A local build is 0.1.0.<timestamp> regardless of the release it was cut from."
    Write-Err2 "Use -Source release for the published build, or -AllowDowngrade to install this one anyway."
    exit 3
}
Confirm-NotADowngrade

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
# $ErrorActionPreference is Stop for the whole script, so a launcher that throws
# rather than returning an exit code would end the run right here - with the IDE
# already closed and nothing to relaunch it. Failing the update is acceptable;
# leaving someone without an IDE is not.
$dirExit = 1
try {
    $dirOut = & $cedtc @dirArgs 2>&1
    $dirExit = $LASTEXITCODE
    $dirOut | ForEach-Object { Write-Note "  | $_" }
} catch {
    Write-Err2 "Director did not run: $($_.Exception.Message)"
}

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
    $probe = Invoke-LocalHttp $healthUrl
    # Any HTTP answer, even 401 when bearer auth is on, means the listener is alive.
    # Only a connection failure keeps us waiting.
    if ($probe.Answered) { $healthy = $true; break }
    Start-Sleep -Seconds 3
}
if ($healthy) {
    # The listener is up; now wait for the workspace to finish indexing so a caller does not race the
    # initial build. Bounded by the same timeout - a stuck build must not hang the self-update forever.
    $phaseDeadline = (Get-Date).AddSeconds($HealthTimeoutSec)
    $phase = "unknown"
    while ((Get-Date) -lt $phaseDeadline) {
        $r = Invoke-LocalHttp $healthUrl
        if ($r.Body) {
            $m = [regex]::Match($r.Body, '"phase"\s*:\s*"([^"]+)"')
            if ($m.Success) { $phase = $m.Groups[1].Value }
            if ($phase -eq "ready") { break }
        }
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
