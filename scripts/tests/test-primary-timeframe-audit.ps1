# Full offline regression through the Testnet candle-quality and signal-funnel audit.
# Executes mocked HTTP only; no MySQL, Binance network, orders or paid LLM.
$ErrorActionPreference='Stop'
$global:historyMockCalls=0
$runner=Join-Path (Split-Path $PSScriptRoot -Parent) 'audit-primary-timeframe-history.ps1'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$stem='SOLUSDT-RETEST-1791845999999-5m-vs-15m-w46-51'
$dest=Join-Path $root 'results'
$paths=@(
    (Join-Path $dest "primary-timeframe-audit-candles-$stem.csv"),
    (Join-Path $dest "primary-timeframe-audit-cross-$stem.csv"),
    (Join-Path $dest "primary-timeframe-audit-trends-$stem.csv"),
    (Join-Path $dest "primary-timeframe-audit-funnel-$stem.csv")
)
foreach($path in $paths){
    if(Test-Path $path){throw "Refusing to overwrite existing real data: $path"}
}
function Invoke-RestMethod {
    [CmdletBinding()]
    param([string]$Uri,[string]$Method,[string]$Body,
          [string]$ContentType,[int]$TimeoutSec)
    switch -Wildcard ($Uri){
        '*/actuator/health' {return [pscustomobject]@{status='UP'}}
        '*/api/v1/backtest/status' {
            return [pscustomobject]@{mode='SIMULATION_ONLY';ordersEnabled=$false}
        }
        '*/api/v1/market-data/settings' {
            return [pscustomobject]@{
                source='TESTNET';websocketEnabled=$false;bootstrapEnabled=$false
                intervals=@('5m','15m','1h')
            }
        }
        '*/api/v1/risk/status' {
            return [pscustomobject]@{configured=$true;ordersEnabled=$false}
        }
        '*/api/v1/backtest/research-config' {
            return [pscustomobject]@{
                ordersEnabled=$false;executionMode='DISABLED'
                symbols=@('SOLUSDT');requireHigherTimeframeAlignment=$false
                requireDominantTakerVolume=$true;breakoutLookback=10
                retestMaxBars=4;maxPrimaryStalenessBars=2
                capitalUsdt='1500';riskPerTradePct='0.5'
                dailyStopPct='3';leverage=1;feePerSidePct='0.05'
                slippagePerSidePct='0.02';breakEvenTriggerR='1'
                structuralAtrBuffer='0.5';maxCostToInitialRiskPct='50'
            }
        }
        'https://demo-fapi.binance.com/fapi/v1/exchangeInfo' {
            return [pscustomobject]@{symbols=@([pscustomobject]@{
                symbol='SOLUSDT';status='TRADING';contractType='PERPETUAL'
                quoteAsset='USDT';filters=@(
                    [pscustomobject]@{filterType='PRICE_FILTER';tickSize='0.01'},
                    [pscustomobject]@{filterType='LOT_SIZE';stepSize='0.001';minQty='0.001'},
                    [pscustomobject]@{filterType='MIN_NOTIONAL';notional='5'}
                )
            })}
        }
        '*/api/v1/backtest/audit-primary-timeframes' {
            $global:historyMockCalls++
            $req=ConvertFrom-Json $Body
            if($req.includeLuna -or -not $req.includeDecisionHistory -or
               $req.pullbackEnabledOverride -or -not $req.breakoutRetestEnabledOverride -or
               [int]$req.steps -ne 3600 -or $req.intervals[0] -ne '5m' -or
               $req.intervals[1] -ne '15m'){
                throw 'Mock audit must require full closed-candle history and no Luna'
            }
            [long]$asOf=[long]$req.asOf
            [long]$from=$asOf-(300L*3600000L)+1
            $candles=@()
            foreach($p in @(@('5m',3600,30,150),@('15m',1200,10,150),@('1h',300,3,180))){
                $candles += [pscustomobject]@{
                    interval=$p[0];expectedBars=$p[1];observedBars=$p[1]
                    zeroTradeBars=$p[2];zeroBaseVolumeBars=$p[2]
                    noActivityBars=$p[2];noActivityPct='1'
                    zeroRangeBars=$p[2];zeroRangeWithTradesBars=0
                    inconsistentVolumeTradeBars=0;longestNoActivityRunBars=3
                    longestNoActivityMinutes=$p[3]
                }
            }
            $cross=@(
                [pscustomobject]@{
                    smallerInterval='5m';largerInterval='15m'
                    expectedRollups=1200;ohlcMismatchRollups=0;volumeMismatchRollups=0
                },
                [pscustomobject]@{
                    smallerInterval='15m';largerInterval='1h'
                    expectedRollups=300;ohlcMismatchRollups=1;volumeMismatchRollups=0
                }
            )
            $arms=@('PRIMARY_5M_CONTEXT_15M','PRIMARY_15M_CONTEXT_1H')
            $trends=@();$funnel=@()
            foreach($arm in $arms){
                $primary=if($arm -eq $arms[0]){'5m'}else{'15m'}
                $higher=if($primary -eq '5m'){'15m'}else{'1h'}
                [int]$decisions=if($primary -eq '5m'){3599}else{1199}
                foreach($frame in @($primary,$higher)){
                    [int]$observed=if($frame -eq $primary){$decisions}else{300}
                    foreach($trend in @('UP','DOWN','RANGE','UNDETERMINED')){
                        $trends += [pscustomobject]@{
                            arm=$arm;interval=$frame
                            role=if($frame -eq $primary){'PRIMARY'}else{'CONTEXT'}
                            trend=$trend;observations=if($trend -eq 'RANGE'){$observed}else{0}
                            totalUniqueCandles=$observed
                        }
                    }
                }
                foreach($p in @(
                    [pscustomobject]@{Stage='NO_SETUP';Reason='NO_SETUP';Count=$decisions-1},
                    [pscustomobject]@{Stage='BREAKOUT_FILTER';Reason='ENTRY_BREAKOUT_FILTER';Count=1}
                )){
                    $funnel += [pscustomobject]@{
                        arm=$arm;stage=$p.Stage;reason=$p.Reason
                        decisions=$p.Count;totalDecisionCandles=$decisions
                        proposedEntries=0;filledEntries=0;blockedByRisk=0
                    }
                }
            }
            return [pscustomobject]@{
                symbol='SOLUSDT';fromOpenTime=$from;asOfCloseTime=$asOf;periodHours=300
                candles=$candles;crossTimeframes=$cross;trends=$trends;funnel=$funnel
            }
        }
        default {throw "Unexpected network/API call in offline test: $Uri"}
    }
}
$tokens=$null;$errors=$null
[System.Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $runner).Path,[ref]$tokens,[ref]$errors) | Out-Null
if($errors.Count -gt 0){throw ("Invalid audit PowerShell syntax: {0}" -f ($errors|Out-String))}
$plan=@(& $runner -PrintOnly)
foreach($key in @('HISTORY_AUDIT_WINDOWS=46..51',
        'HISTORY_AUDIT_NO_SYNC=true','HISTORY_AUDIT_NO_ORDERS=true',
        'HISTORY_AUDIT_NO_LUNA=true','HISTORY_AUDIT_INTERVALS=5m,15m,1h')){
    if($plan -notcontains $key){throw "Frozen audit rule not found: $key"}
}
try {
    & $runner -StartWindow 46 -Windows 6 -AsOf 1791845999999
    if($global:historyMockCalls -ne 6){throw "Expected six readonly audits"}
    foreach($p in $paths){if(-not(Test-Path $p)){throw "Missing forensic audit CSV: $p"}}
    $candles=@(Import-Csv $paths[0]);$cross=@(Import-Csv $paths[1])
    $trends=@(Import-Csv $paths[2]);$funnel=@(Import-Csv $paths[3])
    if($candles.Count -ne 18 -or $cross.Count -ne 12 -or
       $trends.Count -ne 96 -or $funnel.Count -ne 24){
        throw "Unexpected audit CSV row totals"
    }
    if(@($funnel|Where-Object {$_.Arm -eq 'PRIMARY_15M_CONTEXT_1H' -and
              $_.TotalDecisionCandles -eq '1199'}).Count -ne 12){
        throw "15m funnel decision denominator missing"
    }
    if(@($cross|Where-Object {$_.Small -eq '15m' -and
              $_.Large -eq '1h' -and $_.OhlcMismatches -eq '1'}).Count -ne 6){
        throw "1h rollup discrepancy must remain visible in CSV"
    }
    Write-Host 'PASS: 6 retrospective windows; 3 candle frames, 2 rollups, 2 causally evaluated funnels'
} finally {
    foreach($p in $paths){Remove-Item -LiteralPath $p -ErrorAction SilentlyContinue}
}
