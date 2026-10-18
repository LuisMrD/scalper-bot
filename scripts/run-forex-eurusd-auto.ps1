<#
.SYNOPSIS
  ONE COMMAND: Maven tests, isolated MySQL, Spring Boot, public Dukascopy
  BID/ASK EURUSD M1 download, real M15 consolidation and LONG+SHORT paper test.
  Requires only installed Java 25, Maven and existing Docker MySQL. No key.
.EXAMPLE
  .\scripts\run-forex-eurusd-auto.ps1
.EXAMPLE
  .\scripts\run-forex-eurusd-auto.ps1 -PrintOnly
#>
[CmdletBinding()]
param(
    [ValidateSet('EURUSD','GBPUSD','AUDUSD','NZDUSD')][string]$Pair='EURUSD',
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$StartUtc='2024-04-15',
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$UntilUtc='2024-05-31',
    [decimal]$CapitalUsd=600,
    [decimal]$RiskPerTradePct=0.5,
    [decimal]$DailyStopPct=3,
    [ValidateRange(1,100)][int]$Leverage=10,
    [decimal]$CommissionUsdPer100kPerSide=3.5,
    [decimal]$SlippagePipsPerSide=0.2,
    [decimal]$StopBufferPips=1,
    [decimal]$MinUnits=1000,
    [decimal]$UnitStep=1000,
    [ValidateRange(1,200)][int]$MaxHoldingBars=60,
    [ValidateRange(1024,65535)][int]$ServerPort=8095,
    [switch]$PrintOnly
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $from=[DateTimeOffset]::ParseExact($StartUtc,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
    $until=[DateTimeOffset]::ParseExact($UntilUtc,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture,[Globalization.DateTimeStyles]::AssumeUniversal)
    if($until -le $from) { throw 'UntilUtc must be after StartUtc' }
    $importStart=$from.AddDays(-45)
    if(($until-$importStart).TotalDays -gt 99) { throw 'Maximum test horizon is 54 days with 45d warmup' }
    $importFrom=$importStart.ToString('yyyy-MM-dd')
    $url="http://127.0.0.1:$ServerPort"
    $request=[ordered]@{
        pair=$Pair
        startOpenUtc=$from.ToUnixTimeMilliseconds()
        lastOpenUtc=$until.ToUnixTimeMilliseconds()+85500000
        capitalUsd=$CapitalUsd
        riskPerTradePct=$RiskPerTradePct
        dailyStopPct=$DailyStopPct
        leverage=$Leverage
        commissionUsdPer100kPerSide=$CommissionUsdPer100kPerSide
        slippagePipsPerSide=$SlippagePipsPerSide
        stopBufferPips=$StopBufferPips
        minUnits=$MinUnits
        unitStep=$UnitStep
        maxHoldingBars=$MaxHoldingBars
        tickVolumeMidasProxy=$false
    }
    if($PrintOnly) {
        Write-Output 'MODE=FOREX_OTC_PAPER_ONLY'
        Write-Output 'MARKET_DATA=DUKASCOPY_PUBLIC_BID_ASK_M1_NO_KEY'
        Write-Output 'STRATEGY=LONG_WEEKDAYS_AND_SHORT_PRICE_ACTION'
        Write-Output "IMPORT_PERIOD=$importFrom..$UntilUtc"
        Write-Output "TRADE_PERIOD=$StartUtc..$UntilUtc"
        Write-Output 'SWAP_ROLLOVER=NOT_MODELLED'
        Write-Output 'MIDAS_TICK_VOLUME=DISABLED'
        $request | ConvertTo-Json -Depth 8
        return
    }
    if(-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven/Java 25 required' }
    if(-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker required' }
    & (Join-Path $PSScriptRoot 'setup-forex-research-db.ps1')
    if($LASTEXITCODE -ne 0) { throw 'MySQL schema setup failed' }

    Write-Host 'Running Maven tests...' -ForegroundColor Cyan
    & cmd.exe /d /c 'mvn -q clean test'
    if($LASTEXITCODE -ne 0) { throw 'Tests failed: no backtest executed' }

    $env:SERVER_PORT="$ServerPort"
    $env:SPRING_PROFILES_ACTIVE='backtest,forex-research'
    $env:FOREX_CSV_DIR=Join-Path $root 'data\forex'
    $env:TRADING_SYMBOLS='BTCUSDT'
    $env:TRADING_ORDERS_ENABLED='false'
    $env:LUNA_ENABLED='false'
    $env:MARKET_DATA_WS_ENABLED='false'
    $env:MARKET_DATA_BOOTSTRAP_ENABLED='false'
    $env:FOREX_DB_URL='jdbc:mysql://127.0.0.1:3307/trading_bot_forex?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    $env:FOREX_DB_USERNAME='trading'
    $env:FOREX_DB_PASSWORD='trading_local_only'
    $env:TRADING_CAPITAL_USDT='600'
    $env:TRADING_RISK_PER_TRADE_PCT='0.5'
    $env:TRADING_DAILY_STOP_PCT='3'
    $env:TRADING_LEVERAGE='1'
    $env:RISK_FEE_PER_SIDE_PCT='0.05'
    $env:RISK_SLIPPAGE_PER_SIDE_PCT='0.02'
    $env:RISK_MAX_COST_TO_INITIAL_RISK_PCT='50'
    $env:MARKET_DATA_INTERVALS='15m'
    New-Item -ItemType Directory -Path (Join-Path $root 'logs') -Force | Out-Null
    $stamp=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $stdout=Join-Path $root "logs\forex-$stamp.out.log"
    $stderr=Join-Path $root "logs\forex-$stamp.err.log"
    $listener=New-Object System.Net.Sockets.TcpClient
    $portOccupied=$false
    try {
        $listener.Connect('127.0.0.1',$ServerPort)
        $portOccupied=$listener.Connected
    }catch {
        # PowerShell wraps the SocketException in a MethodInvocationException.
        if($_.Exception.InnerException -isnot [System.Net.Sockets.SocketException]) {
            throw
        }
    }finally{ $listener.Dispose() }
    if($portOccupied) { throw "Port $ServerPort already occupied. Stop the other server." }

    Write-Host 'Starting paper-only Forex Spring Boot...' -ForegroundColor Cyan
    $server=Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','mvn -q spring-boot:run' -WorkingDirectory $root -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden
    try {
        $ready=$false
        for($i=0;$i -lt 90;$i++) {
            Start-Sleep -Seconds 2
            if($server.HasExited) { throw "Spring exited; see $stdout and $stderr" }
            try {
                $status=Invoke-RestMethod -Uri "$url/api/v1/research/forex/status" -TimeoutSec 2
                if($status.mode -ceq 'CSV_PAPER_ONLY_NO_ORDERS' -and $status.market -ceq 'FOREX_OTC_SPOT') {
                    $ready=$true
                    break
                }
            }catch {}
        }
        if(-not $ready) { throw "Spring startup timeout; see $stdout / $stderr" }
        Write-Host "Downloading historical $Pair BID/ASK from Dukascopy (free, no key)..." -ForegroundColor Cyan
        $importBody=@{pair=$Pair;fromUtc=$importFrom;untilUtc=$UntilUtc} | ConvertTo-Json -Compress
        $download=Invoke-RestMethod -Method Post -Uri "$url/api/v1/research/forex/import/dukascopy" -Body ([Text.Encoding]::UTF8.GetBytes($importBody)) -ContentType 'application/json; charset=utf-8' -TimeoutSec 2400
        if($download.pair -cne $Pair -or $download.bars15m -lt 300) {
            throw 'Incomplete historical quote import'
        }
        Write-Host "Imported $($download.bars15m) historical M15 candles."
        $payload=$request | ConvertTo-Json -Depth 8 -Compress
        $report=Invoke-RestMethod -Method Post -Uri "$url/api/v1/research/forex/backtest" -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -ContentType 'application/json; charset=utf-8' -TimeoutSec 900
        if($report.market -cne 'FOREX_OTC_SPOT' -or $report.pair -cne $Pair) {
            throw 'Incorrect market/pair in report'
        }
        $results=Join-Path $root 'results'
        New-Item -ItemType Directory -Path $results -Force | Out-Null
        $stem="FOREX-$Pair-LONG-SHORT-$StartUtc-$UntilUtc-$stamp"
        $report | ConvertTo-Json -Depth 24 | Set-Content (Join-Path $results "$stem.json") -Encoding UTF8
        @($report.trades) | Export-Csv (Join-Path $results "$stem-trades.csv") -NoTypeInformation -Encoding UTF8
        $long=@($report.trades | Where-Object { $_.side -eq 'LONG' })
        $short=@($report.trades | Where-Object { $_.side -eq 'SHORT' })
        [pscustomobject]@{
            Pair=$Pair
            LongTrades=$long.Count
            ShortTrades=$short.Count
            RealizedUsd=$report.realizedUsd
            MarkedEquityUsd=$report.finalMarkedEquityUsd
            FeesUsd=$report.feesUsd
            MaxDrawdownPct=$report.worstDrawdownPct
            ResultFile=(Join-Path $results "$stem.json")
        } | Format-List
        Write-Host 'COMPLETE: see results folder for JSON/CSV.' -ForegroundColor Green
    }catch{
        Write-Host "`n========== FOREX ERROR ==========" -ForegroundColor Red
        Write-Host $_.Exception.Message -ForegroundColor Red
        # The last 100 lines often contain only Tomcat stack frames.
        # Surface the FIRST meaningful cause and provider/day failure marker.
        if(Test-Path -LiteralPath $stdout) {
            Write-Host "`n--- Root-cause markers from full Spring stdout ---" -ForegroundColor Yellow
            $markers=@(Select-String -LiteralPath $stdout -Pattern @(
                    'Caused by:', 'Dukascopy', 'Missing minute',
                    'Partial M15', 'Non-weekend missing', 'Invalid Forex',
                    'Unpaired', 'HTTP 429', 'HTTP 503',
                    'org.springframework.web', 'IllegalArgumentException',
                    'IllegalStateException', 'Exception:') |
                    Select-Object -Last 25)
            if($markers.Count -gt 0) {
                $markers | ForEach-Object {
                    Write-Host ("L{0}: {1}" -f $_.LineNumber,$_.Line.Trim())
                }
            } else {
                Write-Host 'No matching root-cause marker in stdout log.'
            }
        }
        foreach($logFile in @($stdout,$stderr)) {
            Write-Host "`n--- $logFile (last 100 lines) ---" -ForegroundColor Yellow
            if(Test-Path -LiteralPath $logFile) {
                try {
                    Get-Content -LiteralPath $logFile -Tail 100 -ErrorAction Stop |
                        ForEach-Object { Write-Host $_ }
                }catch{
                    Write-Host "Could not read log yet: $($_.Exception.Message)"
                }
            }else{
                Write-Host 'Log file not created'
            }
        }
        throw
    }finally{
        if(-not $server.HasExited) {
            & taskkill.exe /PID $server.Id /T /F | Out-Null
        }
    }
}finally{ Pop-Location }
