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
$inSamplePreview = @(& $target -InSample -AsOfEpochMs 1792108799999 -Windows 12 -PrintOnly)
if ($inSamplePreview -notcontains 'EVIDENCE=HISTORICAL_INSAMPLE_ONLY' -or
    $inSamplePreview -notcontains 'WINDOWS=12; WINDOW_BARS=3600') {
    throw 'Insample preview did not preserve the original 12x900h selection window'
}
$oosPreview = @(& $target -AsOfEpochMs 1795348799999 -Windows 1 -PrintOnly)
if ($oosPreview -notcontains 'EVIDENCE=STRICT_PROSPECTIVE_OOS') {
    throw 'The prospective runner lost its strict OOS label'
}
Write-Host 'OK: both historical and prospective dry-run plans are correctly labeled.'

