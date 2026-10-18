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
