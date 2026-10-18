<#
.SYNOPSIS
  No-network PowerShell 5.1+ syntax regression check for the OOS runner.
.EXAMPLE
  .\scripts\tests\test-btc-15m-shared-oos-syntax.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'run-btc-15m-shared-oos.ps1'
$tokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    $target,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    foreach ($error in $parseErrors) {
        Write-Error "Syntax failure at line $($error.Extent.StartLineNumber): $($error.Message)"
    }
    throw "OOS PowerShell runner has $($parseErrors.Count) syntax error(s)"
}
Write-Host 'OK: shared OOS PowerShell runner parses without errors.'
# Regression: the per-window prospective cutoff must NOT reject historical
# holdout. The previous bug only surfaced during the LIVE window loop.
$source=[System.IO.File]::ReadAllText($target)
$protected='if (-not $InSample -and -not $HistoricalHoldout -and $start -lt $oosStart) {'
if (-not $source.Contains($protected)) {
    throw 'Regression: 2023-2024 historical holdout would be rejected as prospective OOS'
}
Write-Host 'OK: historical holdout is not gated by the prospective OOS cutoff.'

foreach ($name in @('start-btc-historical-research.ps1','setup-btc-historical-research-db.ps1')) {
    $extra=Join-Path (Split-Path -Parent $PSScriptRoot) $name
    $extraTokens=$null
    $extraErrors=$null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $extra,[ref]$extraTokens,[ref]$extraErrors)
    if ($extraErrors.Count -gt 0) {
        throw "$name has $($extraErrors.Count) PowerShell syntax error(s): $($extraErrors[0].Message)"
    }
}
$inSamplePreview = @(& $target -InSample -AsOfEpochMs 1792108799999 -Windows 12 -PrintOnly)
if ($inSamplePreview -notcontains 'EVIDENCE=HISTORICAL_INSAMPLE_ONLY' -or
    $inSamplePreview -notcontains 'WINDOWS=12; WINDOW_BARS=3600') {
    throw 'Insample preview did not preserve the original 12x900h selection window'
}
$oosPreview = @(& $target -AsOfEpochMs 1795348799999 -Windows 1 -PrintOnly)
if ($oosPreview -notcontains 'EVIDENCE=STRICT_PROSPECTIVE_OOS') {
    throw 'The prospective runner lost its strict OOS label'
}
$holdoutPreview = @(& $target -HistoricalHoldout -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1735689599999 -Windows 12 -PrintOnly)
if ($holdoutPreview -notcontains 'EVIDENCE=PRE_FROZEN_HISTORICAL_HOLDOUT' -or
    $holdoutPreview -notcontains 'SOURCE=production') {
    throw 'The historical holdout preview did not preserve the separate pre-frozen label'
}
$startPreview = @(& (Join-Path (Split-Path -Parent $PSScriptRoot) 'start-btc-historical-research.ps1') -PrintOnly)
if ($startPreview -notcontains 'SOURCE=PRODUCTION_PUBLIC_READ_ONLY') {
    throw 'Research bootstrap preview did not require read-only historical production source'
}
Write-Host 'OK: all three research modes and isolated database startup preview are labeled correctly.'

