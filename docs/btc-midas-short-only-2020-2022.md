# BTCUSDT — MIDAS SHORT ONLY — two untouched historical cohorts (research only)

This follow-up study was **specified after** examining 2023-2024 and
2025-2026. Those past windows are NOT independent evidence for the decision
to abandon LONG. This study instead fixes **two disjoint older cohorts** and
does NOT change trading thresholds after looking at outcomes.

## Frozen model and three independent books

- Only **SHORT** price-action/volume mixed pullback signals can be computed.
  Original MIDAS retest confirmation for SHORT, CLOSED daily EMA21 trend
  alignment, original stop, trailing and DYNAMIC_TREND_60 exit remain.
- No LONG detector, LONG paper portfolio, LONG signal event, LONG trade,
  opposite-side position or LLM call is used in either cohort.
- CONTROL: SHORT BASELINE (unchanged); CANDIDATE_A: SHORT COMBINED_045
  (weekday + stop distance <=0.45%); CANDIDATE_B: SHORT COMBINED_050
  (weekday + stop distance <=0.50%). Candidate names are retained for
  apples-to-apples research reporting, but LONG does not exist here.
- Each independently replayed policy starts with US$600 **per window**;
  one position at a time, shared cost/cash/drawdown accounting, 0.5%
  risk per trade, 3% daily stop, leverage 1x. Original request parameters
  are checked before loading market data.
- Fee 0.05% per side, adverse slippage 0.02% per side, spread 0.01%,
  **assumed funding zero**, maintenance margin hypothesis 0.5%, maximal
  cost-to-risk 50%, price tick/quantity/lot from live public exchangeInfo.
  These are hypothetical costs; real historical funding, liquidity,
  contract metadata and leverage constraints may differ.

### Locked 900-hour cohorts

| ID | Fixed first decision open (UTC) | Final close (UTC) | Windows | Test label |
|---|---|---|---|---|
| 2020_2021 | 2020-07-08 00:00 | 2021-09-30 23:59:59.999 | 12 × 900h | SHORT_ONLY_HOLDOUT_2020_21 |
| 2021_2022 | 2021-10-08 00:00 | 2022-12-31 23:59:59.999 | 12 × 900h | SHORT_ONLY_HOLDOUT_2021_22 |

The 7-day gap is intentional. No continuous backtest or compounding
across cohorts is claimed. The results can be interpreted as independent
historical holdouts **only if those candles were never used** to choose
the currently frozen MIDAS/PA/Volume rules and SHORT-only preference.

The endpoint only accepts one of twelve **fixed** windows per cohort;
arbitrary dates are rejected. The 1000 15m warmup candles and 60 closed
daily candles needed before each window do not generate trades.

## Commands — Windows PowerShell 5.1, Spring Boot Java 25

### Terminal 1: stop previous Spring, update and start research

Keep the existing MySQL container with historical data; no reset.
The separate research schema is \`trading_bot_research\`, never the
testnet \`trading_bot\`. After stopping the old Spring process:

~~~powershell
cd C:\Projetos\scalper-bot
git switch feat/btc-midas-shared-oos-validation
git pull --ff-only

mvn clean test
if ($LASTEXITCODE -ne 0) { throw 'Java unit tests failed' }

.\scripts\start-btc-historical-research.ps1
~~~

### Terminal 2: sync *previously unseen* BTCUSDT candles

Use public Binance USD-M Futures production **OHLCV only**. No real orders:

~~~powershell
cd C:\Projetos\scalper-bot

# Dry run, no network:
.\scripts\tests\test-btc-15m-shared-oos-syntax.ps1

# 15m + closed 1d, with margin for MIDAS and EMA21 warmup:
.\scripts\sync-independent-daily-weekly.ps1 -Symbols BTCUSDT -Intervals '15m','1d' -FromUtc '2020-05-01' -UntilUtc '2022-12-31' -FailOnEmptyDaily
~~~

The sync script is resumable/idempotent. Historical candles must be
contiguous; failed/missing ranges must not be interpolated or manufactured.
Timeframe \`1d\` includes only fully closed higher-timeframe bars.

### Terminal 2: smoke test one window, then full cohorts

~~~powershell
# One window nearest the end of each cohort:
.\scripts\run-btc-15m-shared-oos.ps1 -ShortOnlyCohort 2020_2021 -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1633046399999 -Windows 1
.\scripts\run-btc-15m-shared-oos.ps1 -ShortOnlyCohort 2021_2022 -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1672531199999 -Windows 1

# Full 12 windows EACH. Do not tune the dates after inspecting results:
.\scripts\run-btc-15m-shared-oos.ps1 -ShortOnlyCohort 2020_2021 -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1633046399999 -Windows 12
.\scripts\run-btc-15m-shared-oos.ps1 -ShortOnlyCohort 2021_2022 -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1672531199999 -Windows 12
~~~

Runner checks for SHORT-only event and trade sides; any LONG is a hard
failure. Output includes 4 files in \`results/\` for EACH cohort:

- \`shared-windows-BTC-MIDAS-SHARED-SHORT_ONLY_HOLDOUT_*.csv\`
- \`shared-trades-BTC-MIDAS-SHARED-SHORT_ONLY_HOLDOUT_*.csv\`
- \`shared-events-BTC-MIDAS-SHARED-SHORT_ONLY_HOLDOUT_*.csv\`
- \`shared-config-BTC-MIDAS-SHARED-SHORT_ONLY_HOLDOUT_*.json\`

Compare realized and marked results, profit factor, fees, each window,
max drawdown per window, win/loss distributions and concentration in
top two winning trades. Three policies must receive the same closed-candle
tape, not filtered CSV trades from another policy. Do not choose a new
threshold merely because one looks better on these cohorts; that would
turn the historical holdout into additional training data.

**Limitations**: test results are simulated with zero assumed funding,
approximate liquidation and current symbol trading filters. OOS performance
requires truly untouched data. Neither results nor code make a live-trading
profitability claim.
