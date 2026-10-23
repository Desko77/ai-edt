<#
.SYNOPSIS
  The bundle decisions of scripts/edt-selfupdate.ps1, on made-up directories.

.DESCRIPTION
  The self-update script decides which bundles a shared delivery installs, which version an
  installation effectively holds, and which legacy artifacts stop a run. Those decisions are
  pure functions over a directory, a file or a report, and this checks them against made-up
  ones - the script body itself (which closes sessions and runs directors) is never executed.

  The script is PARSED rather than run: parsing validates its syntax, and the function
  definitions are lifted out of the syntax tree and dot-sourced here, so no top-level
  statement of the script - no process listing, no p2 call - ever runs in this test.

  Exit code 0 = every check held; 1 = a check failed or the script did not parse.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = (Resolve-Path (Join-Path $scriptDir '..\edt-selfupdate.ps1')).Path

# --- parse: the syntax gate --------------------------------------------------
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $target, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    Write-Host "FAIL: edt-selfupdate.ps1 does not parse:"
    foreach ($e in $parseErrors) { Write-Host ("  " + $e.Message) }
    exit 1
}

# --- lift the functions without running the script ---------------------------
$functions = $ast.FindAll({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true)
foreach ($f in $functions) {
    . ([scriptblock]::Create($f.Extent.Text))
}

# Find-RepoBundles reads the bundle the caller named; the param block that would set it
# never ran here, so the value the real caller passes is stated.
$BundleSymbolicName = 'ru.aiedt.mcp.server'
$script:SharedDeliveryFloor = '0.2.60'
$script:OformBundle = 'ru.aiedt.mcp.oform'
$script:LegacyOformFeatureGroupIU = 'ru.aiedt.mcp.oform.feature.feature.group'

# --- a tiny assertion set ----------------------------------------------------
$script:failed = 0
$script:ran = 0
function Assert-True([bool]$condition, [string]$name) {
    $script:ran += 1
    if ($condition) {
        Write-Host ("ok   " + $name)
    } else {
        $script:failed += 1
        Write-Host ("FAIL " + $name) -ForegroundColor Red
    }
}

function New-TempDir {
    $dir = Join-Path ([System.IO.Path]::GetTempPath()) ("aiedt-selfupdate-test-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $dir | Out-Null
    return $dir
}

# Bundle selection is validated by PowerShell before any script statement can run.
$bundleParameter = @($ast.ParamBlock.Parameters | Where-Object {
    $_.Name.VariablePath.UserPath -eq 'BundleSymbolicName'
})
$validation = @($bundleParameter.Attributes | Where-Object { $_.TypeName.Name -eq 'ValidateSet' })
Assert-True (($validation.Count -eq 1) -and
    $validation[0].PositionalArguments.Count -eq 1 -and
    $validation[0].PositionalArguments[0].Value -eq 'ru.aiedt.mcp.server') `
    "the bundle parameter permits only the server, never a pair comparing oform to itself"

# --- the version floor -------------------------------------------------------
Assert-True ((Test-VersionAtLeast '0.2.60' '0.2.60') -eq $true) "0.2.60 is at the floor"
Assert-True ((Test-VersionAtLeast '0.2.59' '0.2.60') -eq $false) "0.2.59 is below the floor"
Assert-True ((Test-VersionAtLeast '0.2.60.v202610171200' '0.2.60') -eq $true) "a build OF 0.2.60 is of the shared delivery"
Assert-True ((Test-VersionAtLeast '0.3.0' '0.2.60') -eq $true) "0.3.0 is above the floor"
Assert-True ((Test-VersionAtLeast '0.2.6' '0.2.60') -eq $false) "0.2.6 is below 0.2.60, numbers not text"
Assert-True ((Test-VersionAtLeast '' '0.2.60') -eq $false) "an empty version is below the floor"

# --- the repository has to offer both bundles of a shared delivery -----------
$repo = New-TempDir
New-Item -ItemType Directory -Path (Join-Path $repo 'plugins') | Out-Null
Set-Content -LiteralPath (Join-Path $repo 'content.jar') -Value ""

$old = Find-RepoBundles $repo
Assert-True ($null -eq $old.ServerVersion) "an empty repository offers no server version"

# An old delivery: the server alone, as every release before 0.2.60 shipped.
Set-Content -LiteralPath (Join-Path $repo 'plugins\ru.aiedt.mcp.server_0.2.35.jar') -Value ""
$old = Find-RepoBundles $repo
Assert-True ($old.ServerVersion -eq '0.2.35') "the server version is read from the jar name"
Assert-True ($null -eq $old.OformVersion) "an old repository carries no oform, and that is the norm"

# A shared delivery: both bundles at one version.
Set-Content -LiteralPath (Join-Path $repo 'plugins\ru.aiedt.mcp.server_0.2.60.jar') -Value ""
Set-Content -LiteralPath (Join-Path $repo 'plugins\ru.aiedt.mcp.oform_0.2.60.jar') -Value ""
$shared = Find-RepoBundles $repo
Assert-True ($shared.ServerVersion -eq '0.2.60') "the highest server version wins over the older jar"
Assert-True ($shared.OformVersion -eq '0.2.60') "the oform bundle of the same delivery is read"

# A repository of the shared delivery WITHOUT the second bundle: the shape selfupdate refuses.
$half = New-TempDir
New-Item -ItemType Directory -Path (Join-Path $half 'plugins') | Out-Null
Set-Content -LiteralPath (Join-Path $half 'plugins\ru.aiedt.mcp.server_0.2.60.jar') -Value ""
$halfFound = Find-RepoBundles $half
Assert-True (($halfFound.ServerVersion -eq '0.2.60') -and ($null -eq $halfFound.OformVersion)) `
    "a server of 0.2.60 with no oform jar is half a delivery, visible as such"

# The feature jar of the private delivery is NOT the bundle jar, and must not read as one.
Set-Content -LiteralPath (Join-Path $half 'plugins\ru.aiedt.mcp.oform.feature_0.1.0.jar') -Value ""
$withFeature = Find-RepoBundles $half
Assert-True ($null -eq $withFeature.OformVersion) "a legacy feature jar is not the oform bundle"

Remove-Item -LiteralPath $repo -Recurse -Force
Remove-Item -LiteralPath $half -Recurse -Force

# --- the version an installation EFFECTIVELY holds ---------------------------
# The report answers versions per record, and only the record the launcher loads decides
# what runs; within it, the highest version is the effective one - the configurator loads
# one bundle, not a list.
function New-Record([string]$kind, [bool]$active, [string[]]$versions, [string]$area = 'C:\edt') {
    return [PSCustomObject]@{
        kind = $kind; active = $active; versions = $versions
        bundlesInfo = (Join-Path $area 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info') }
}

$records = @(
    (New-Record 'profile' $false @('0.2.61')),
    (New-Record 'installation' $true @('0.2.60', '0.2.60.202610171200')))
Assert-True ((Get-RecordedVersion $records) -eq '0.2.60.202610171200') `
    "the effective version is the highest of the record the launcher loads"
Assert-True ((Get-RecordedVersion @((New-Record 'installation' $true @('0.2.60')))) -eq '0.2.60') `
    "one version in the loaded record is the effective one"
Assert-True ($null -eq (Get-RecordedVersion @((New-Record 'profile' $false @('0.2.61'))))) `
    "a record the launcher ignores decides nothing"
Assert-True ($null -eq (Get-RecordedVersion @())) "no records at all answers nothing"

# --- a shared delivery is confirmed by the EFFECTIVE version of BOTH bundles --
# Membership in the recorded list is not a confirmation: a newer non-matching oform beside
# the matching one leaves the newer one as what runs.
Assert-True (Test-SharedDeliveryConfirmed '0.2.60' '0.2.60' '0.2.60') `
    "both bundles at the installed version confirm the delivery"
Assert-True (-not (Test-SharedDeliveryConfirmed '0.2.60' '0.2.61' '0.2.60')) `
    "a newer non-matching oform beside the matching server is not a confirmation"
Assert-True (-not (Test-SharedDeliveryConfirmed '0.2.60' '0.2.60.202610170900' '0.2.60')) `
    "an oform of another build of the same numbers is not a confirmation"
Assert-True (-not (Test-SharedDeliveryConfirmed '0.2.60' $null '0.2.60')) `
    "no oform version is not a confirmation"
Assert-True (-not (Test-SharedDeliveryConfirmed '0.2.61' '0.2.60' '0.2.60')) `
    "a server above the installed version is not a confirmation either"

# --- the loaded area of an installation, from the report ----------------------
$areaReport = [PSCustomObject]@{
    installations = @([PSCustomObject]@{
        install = 'E:\EDT\A'
        records = @((New-Record 'installation' $true @('0.2.60'))) })
    consumers = @(
        [PSCustomObject]@{ install = 'E:\EDT\A'; loads = 'E:\EDT\A\configuration' },
        [PSCustomObject]@{ install = 'E:\EDT\B'; loads = 'E:\EDT\A\configuration' }) }
Assert-True ((Get-LoadedAreaForInstall $areaReport 'E:\EDT\A') -ne '') `
    "the loaded area of an installation is answered"
Assert-True ((Get-LoadedAreaForInstall $areaReport 'E:\EDT\B') -eq (Get-LoadedAreaForInstall $areaReport 'E:\EDT\A')) `
    "an installation loading another's area is answered by the consumers list"
Assert-True ((Get-LoadedAreaForInstall $areaReport 'E:\EDT\C') -eq '') `
    "an installation the report does not name answers nothing"

# --- the installed legacy state, from the p2 profile of the loaded area -------------
# Installed membership is what the p2 profile resolved, read by the report into
# p2Profiles keyed by the normalized area ROOT; a feature jar cached under features/ or
# plugins/ proves nothing either way, and a profile the report could not read leaves the
# question undecided, which refuses rather than reads as absence.
function New-LegacyUnit([string]$id, [string]$version) {
    return [PSCustomObject]@{ id = $id; version = $version }
}
$profileWithLegacy = [PSCustomObject]@{
    status = 'known'; profileId = 'DefaultProfile'; snapshot = '1791574315314'
    legacyUnits = @(
        (New-LegacyUnit 'ru.aiedt.mcp.oform.feature.feature.group' '0.1.0.202609212242'),
        (New-LegacyUnit 'ru.aiedt.mcp.oform.feature.feature.jar' '0.1.0.202609212242')) }
$profileClean = [PSCustomObject]@{
    status = 'known'; profileId = 'DefaultProfile'; snapshot = '1791574315315'
    legacyUnits = @() }
$profileUnreadable = [PSCustomObject]@{
    status = 'unknown'
    reason = 'the latest profile snapshot (1791574315314.profile.gz) cannot be parsed: not a profile document' }

# The report key is the area ROOT, normalized; the lookup takes the loaded area itself.
$p2Report = [PSCustomObject]@{
    installations = @(); unclaimed = @(); consumers = @()
    p2Profiles = [PSCustomObject]@{
        'e:\edt\a' = $profileWithLegacy
        'e:\edt\b' = $profileClean
        'e:\edt\c' = $profileUnreadable } }
Assert-True ((Get-AreaP2Status $p2Report 'E:\EDT\A\configuration') -eq $profileWithLegacy) `
    "the p2 status of a loaded area is read by its normalized area root"
Assert-True ($null -eq (Get-AreaP2Status $p2Report 'E:\EDT\missing\configuration')) `
    "an area the report holds no p2 state for answers nothing"
Assert-True ($null -eq (Get-AreaP2Status ([PSCustomObject]@{ installations = @() }) 'E:\EDT\A\configuration')) `
    "a report without p2 states answers nothing"
Assert-True ($null -eq (Get-AreaP2Status $p2Report '')) "no area answers nothing"

# The verdict: known state decides by the exact installed units, unreadable state refuses.
$verdictLegacy = Get-LegacyOformVerdict $p2Report 'E:\EDT\A\configuration'
Assert-True ((-not $verdictLegacy.Refuse) -and $verdictLegacy.Units.Count -eq 2) `
    "a profile installing both legacy units reports them as the refusal will name them"
$verdictClean = Get-LegacyOformVerdict $p2Report 'E:\EDT\B\configuration'
Assert-True ((-not $verdictClean.Refuse) -and $verdictClean.Units.Count -eq 0) `
    "a known profile without the legacy units is a clean verdict, not an unknown one"
$verdictUnknown = Get-LegacyOformVerdict $p2Report 'E:\EDT\C\configuration'
Assert-True ($verdictUnknown.Refuse -and $verdictUnknown.Reason -match 'cannot be parsed') `
    "a profile that cannot be read refuses with its reason, never reads as legacy-free"
$verdictAbsent = Get-LegacyOformVerdict $p2Report 'E:\EDT\missing\configuration'
Assert-True ($verdictAbsent.Refuse) `
    "an area with no p2 state in the report refuses: absence of the answer is not absence of the feature"

# Public deliveries before the shared feature do not require ordinary-profile discovery.
$oldLegacy = Get-LegacyOformVerdict $p2Report 'E:/EDT/A/configuration' '0.2.59'
Assert-True ((-not $oldLegacy.Refuse) -and $oldLegacy.Units.Count -eq 0) `
    "an older public delivery does not require legacy-feature migration"
$oldUnknown = Get-LegacyOformVerdict $null '' '0.2.59'
Assert-True ((-not $oldUnknown.Refuse) -and $oldUnknown.Units.Count -eq 0) `
    "an older public delivery does not require an installed ordinary-profile report"

# --- the legacy IU is named to a human and handed to no director --------------
# The refusal names the legacy feature and prints the director step that removes it; the
# script must never run that step itself. Every line naming the legacy IU constant is
# either its definition or a Write-* message - a director argument built out of it would
# be neither.
$legacyLines = @(Get-Content -LiteralPath $target | Where-Object { $_ -match 'LegacyOformFeatureGroupIU' })
Assert-True ($legacyLines.Count -ge 1) "the legacy feature group IU is named somewhere"
$misused = @($legacyLines | Where-Object { $_ -notmatch '\$script:LegacyOformFeatureGroupIU\s*=' -and $_ -notmatch 'Write-(Note|Err2|Warn2|Step)' })
Assert-True ($misused.Count -eq 0) ("the legacy IU is never given to a director: " + ($misused -join ' | '))

# The migration hint is a director command of the shape this installer itself runs: the
# same application, the legacy group IU as its only -uninstallIU, and no install of anything.
$hintLines = @(Get-Content -LiteralPath $target |
    Where-Object { $_ -match '1cedtc\.exe .*org\.eclipse\.equinox\.p2\.director' -and $_ -match 'Write-Note' })
Assert-True ($hintLines.Count -eq 1) "one refusal prints the migration hint, shared by both modes"
Assert-True ($hintLines[0] -match '-application org\.eclipse\.equinox\.p2\.director') `
    "the hint names the director application this installer runs"
Assert-True ($hintLines[0] -match '-uninstallIU \$\(\$script:LegacyOformFeatureGroupIU\)') `
    "the hint uninstalls exactly the legacy group IU"
Assert-True ($hintLines[0] -notmatch '-installIU') "the migration hint installs nothing"

# --- the version one AREA records, whoever holds the record ------------------------
# An installation that loads another one's area through -configuration owns no record
# there and still runs what that area records, so the area - not the installation - is
# what the delivery confirmation reads. Unclaimed records of the area count too: their
# owner being gone does not unload the bundle.
$areaVersionReport = [PSCustomObject]@{
    installations = @(
        [PSCustomObject]@{
            install = 'E:\EDT\A'
            records = @(
                (New-Record 'installation' $true @('0.2.60') 'E:\EDT\A'),
                (New-Record 'profile' $false @('0.2.59') 'E:\EDT\A')) })
    unclaimed = @(
        [PSCustomObject]@{
            kind = 'unclaimed'
            bundlesInfo = 'E:\EDT\A\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
            versions = @('0.2.60.202610171200') }) }
Assert-True ((Get-AreaVersion $areaVersionReport 'E:\EDT\A\configuration') -eq '0.2.60.202610171200') `
    "the effective version of an area is the highest every record there holds, unclaimed included"
Assert-True ((Get-AreaVersion $areaVersionReport 'e:\edt\a\configuration') -eq '0.2.60.202610171200') `
    "the area compares normalised, whatever case either side spells it in"
Assert-True ($null -eq (Get-AreaVersion $areaVersionReport 'E:\EDT\elsewhere\configuration')) `
    "an area holding no records answers no version"
Assert-True ($null -eq (Get-AreaVersion $areaVersionReport '')) "no area answers no version"

# A newer non-matching oform beside the matching one is what runs: the area maximum, not
# membership in the list, is what the confirmation compares.
$mixedArea = [PSCustomObject]@{
    installations = @([PSCustomObject]@{
        install = 'E:\EDT\A'
        records = @((New-Record 'installation' $true @('0.2.60', '0.2.61') 'E:\EDT\A')) })
    unclaimed = @() }
Assert-True ((Get-AreaVersion $mixedArea 'E:\EDT\A\configuration') -eq '0.2.61') `
    "the area maximum answers, so a newer non-matching bundle is not hidden by a matching one"

# --- a matching borrowed configuration confirms the delivery ------------------------
# The borrower loads the owner's area (consumers), owns no record of its own there, and
# the delivery is still confirmed: the effective versions of BOTH bundles read from the
# AREA it loads, exactly as the legacy check reads the same area's profile.
$borrowed = [PSCustomObject]@{
    installations = @([PSCustomObject]@{
        install = 'E:\EDT\owner'
        records = @((New-Record 'installation' $true @('0.2.60') 'E:\EDT\owner')) })
    unclaimed = @()
    consumers = @(
        [PSCustomObject]@{ install = 'E:\EDT\owner'; loads = 'E:\EDT\owner\configuration' },
        [PSCustomObject]@{ install = 'E:\EDT\borrower'; loads = 'E:\EDT\owner\configuration' }) }
$borrowedOform = [PSCustomObject]@{
    installations = @([PSCustomObject]@{
        install = 'E:\EDT\owner'
        records = @((New-Record 'installation' $true @('0.2.60') 'E:\EDT\owner')) })
    unclaimed = @() }
$borrowedArea = Get-LoadedAreaForInstall $borrowed 'E:\EDT\borrower'
Assert-True ($borrowedArea -eq (Get-NormalPath 'E:\EDT\owner\configuration')) `
    "the borrower's loaded area is answered through the consumers list"
Assert-True (Test-SharedDeliveryConfirmed `
        (Get-AreaVersion $borrowed $borrowedArea) `
        (Get-AreaVersion $borrowedOform $borrowedArea) `
        '0.2.60') `
    "a fully known matching borrowed configuration confirms the delivery"
Assert-True (-not (Test-SharedDeliveryConfirmed `
        (Get-AreaVersion $borrowed $borrowedArea) `
        (Get-AreaVersion ([PSCustomObject]@{ installations = @(); unclaimed = @() }) $borrowedArea) `
        '0.2.60')) `
    "a borrowed area recording no oform does not confirm the delivery"

# Profile discovery uses the final repository version and precedes the session-close block.
$profileAssignment = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
    $node.Left.Extent.Text -eq '$legacyReport' }, $true)
$profileGuard = $profileAssignment.Parent
while ($profileGuard -and -not (
    $profileGuard -is [System.Management.Automation.Language.IfStatementAst] -and
    $profileGuard.Clauses[0].Item1.Extent.Text -like '*SharedDeliveryFloor*')) {
    $profileGuard = $profileGuard.Parent
}
Assert-True ($null -ne $profileGuard) "single-session profile discovery is guarded by the shared-delivery floor"
$finalSelection = @($ast.EndBlock.Statements | Where-Object { $_.Extent.Text -eq 'Confirm-NotADowngrade' })
Assert-True (($finalSelection.Count -eq 1) -and
    $profileGuard.Extent.StartOffset -gt $finalSelection[0].Extent.EndOffset) `
    "profile discovery follows the final repository selection, including automatic fallback"
if ($profileGuard) {
    $repoVersion = '0.2.59'
    $script:profileCalls = 0
    function Get-InstallationReport { $script:profileCalls += 1; throw 'Unexpected profile discovery' }
    & ([scriptblock]::Create($profileGuard.Extent.Text))
    Assert-True ($script:profileCalls -eq 0) "single-session updates before the floor do not discover an ordinary profile"
}

# The extracted parameter block has no installer body or process access.
$bindOnly = [scriptblock]::Create($ast.ParamBlock.Extent.Text + [Environment]::NewLine + "'bound'")
Assert-True ((& $bindOnly -BundleSymbolicName 'ru.aiedt.mcp.server') -eq 'bound') `
    "the server bundle binds successfully before installer execution"
$rejectedOform = $false
try { & $bindOnly -BundleSymbolicName 'ru.aiedt.mcp.oform' | Out-Null }
catch [System.Management.Automation.ParameterBindingException] { $rejectedOform = $true }
Assert-True $rejectedOform "the oform bundle is refused during parameter binding"

Assert-True (Test-OrdinaryFormsRegistered '{"ordinaryFormsRegistered":true}') `
    "live registration confirms the ordinary runtime"
foreach ($body in @('', '{}', '{"phase":"ready"}', '{"ordinaryFormsRegistered":false}',
    '{"ordinaryFormsRegistered":"true"}', 'not-json')) {
    Assert-True (-not (Test-OrdinaryFormsRegistered $body)) `
        "missing, false or non-boolean registration cannot confirm the runtime: $body"
}

# --- the verdict --------------------------------------------------------------
Write-Host ""
if ($script:failed -gt 0) {
    Write-Host ("FAIL: " + $script:failed + " of " + $script:ran + " checks failed")
    exit 1
}
Write-Host ("ok: " + $script:ran + " checks held, edt-selfupdate.ps1 parses")
exit 0
