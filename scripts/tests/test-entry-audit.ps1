# Offline smoke test: PowerShell parses and 9 windows are complete/disjoint.
$ErrorActionPreference='Stop'
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'audit-losing-entries.ps1'
if (-not (Test-Path $runner)) { throw 'Missing entry audit script' }
$tokens=$null
$parseErrors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$parseErrors) | Out-Null
if($parseErrors.Count -gt 0) { throw ("PowerShell parser errors: {0}" -f ($parseErrors | ForEach-Object Message | Out-String)) }
$printed=@(& $runner -Symbol SOLUSDT -Setup RETEST -Windows 9 -AsOf 1791845999999 -PrintOnly)
foreach ($required in @('AUDIT_WINDOWS=9','AUDIT_TIMEFRAME=5m','AUDIT_CANDLES=3600','AUDIT_NO_LUNA=true','AUDIT_NO_ORDERS=true')) {
  if ($printed -notcontains $required) { throw ("Missing printed guarantee: {0}" -f $required) }
}
$rows=@($printed | Where-Object { $_ -like 'AUDIT_WINDOW=*' })
if($rows.Count -ne 9) { throw 'Expected nine entry windows' }
for($i=0;$i -lt 9;$i++) {
  if ($rows[$i] -notmatch '^AUDIT_WINDOW=(\d+);FROM=(\d+);ASOF=(\d+)$') {
    throw ("Malformed window: {0}" -f $rows[$i])
  }
  $w=[int]$Matches[1]
  [long]$start=[long]$Matches[2]
  [long]$end=[long]$Matches[3]
  [long]$shouldEnd=1791845999999-([long]$i*300L*3600000L)
  if ($w -ne ($i+1) -or $end -ne $shouldEnd -or
      $end-$start+1 -ne (300L*3600000L)) { throw ("Incorrect window {0}" -f $w) }
}
Write-Host 'PASS: entry audit plan 9 x 300h, Java-only, no orders'
