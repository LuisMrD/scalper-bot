# Forex OTC EUR/USD research — FIRST safe migration from Binance

## Quote-gap fix (2026-10-18)

The Exness import successfully downloaded quotes but previously rejected a
one-hour gap **2024-03-28 23:00 UTC → 2024-03-29 00:00 UTC**
(three missing 15-minute opens). Such missing quote periods cannot be
filled with fabricated price candles. The reader now tolerates bounded
short (at most 4h) gaps and presumed weekend/extended holiday closures
while recording real timestamps. Unexplained longer weekday gaps still
fail validation.

During paper replay each interruption resets 15-minute price-action
structure and EMA warmup; signals require another 80 uninterrupted
15-minute bars. If an open position crosses an unobserved *midweek*
quote interval, the backtest fails rather than making up a stop path.
A true weekend position is marked/processed at the actual reopening
quotes, with financing/swap still unmodelled.

Reports include `quoteGapCount`, `missingQuoteBars` and
`skippedWarmupBars`. On Fridays the runner automatically clips the
requested backtest end to the last genuinely observed M15 candle and
prints that adjustment (rather than inventing 23:45 quotes).

These safeguards make a historical research run possible despite ordinary
sparse provider observations, but missing intrabar quotes still limit
what can be asserted about execution. Consult these audit fields before
interpreting any profitability.

---

## Update: Exness is now the default history source (October 2026)

The original Dukascopy historical M1 endpoint returned **HTTP 403** from
the developer's Windows machine, so the one-command script no longer depends
on that origin. It now uses the Exness **Standard-account** public monthly
EURUSD tick archives, containing both actual BID and ASK, to produce M15 bars.

- Public historical source: https://www.exness.com/tick-history/
- Documented archive path:
  `https://ticks.ex2archive.com/ticks/EURUSD/2024/03/Exness_EURUSD_2024_03.zip`
- No API key, demo credentials, Python or manual CSV input is required.
- Source is **indicative OTC quotes**, not consolidated Forex prices.
- On failure, the script does **not** replace the last validated CSV.
- It does **not** manufacture volume, spread or synthetic minute prices.
- To explicitly use the old provider (which may return 403):
  `./scripts/run-forex-eurusd-auto.ps1 -DataProvider Dukascopy`.

Run:
~~~powershell
cd C:\Projetos\scalper-bot
git switch feat/forex-eurusd-csv-research
git pull --ff-only
.\scripts\run-forex-eurusd-auto.ps1
~~~

This still runs **one shared USD paper wallet** with LONG + SHORT, not live
trades. It assumes fixed research commission and slippage and ignores
overnight FX swaps; do not treat profits as a production forecast.

---

## ONE-COMMAND EXECUTION — free public Exness, LONG + SHORT

**Preferred workflow:** no brokerage account, API key, manual CSV export, Python
or CSV conversion needed. Java 25 / Maven / the existing local Docker MySQL
are enough. Dukascopy's public historical BID and ASK M1 candle files use
zero-based months and LZMA compression, decoded with XZ for Java 1.12.
Only complete 15-minute buckets (15 actual BID and ASK M1 candles) are used.

Run in Windows PowerShell:

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/forex-eurusd-csv-research
git pull --ff-only
.\scripts\run-forex-eurusd-auto.ps1
~~~

The command automatically:

1. Creates or reuses a separate local MySQL schema named
   \`trading_bot_forex\`, without deleting other databases.
2. Runs \`mvn clean test\`, launches the paper-only server on 127.0.0.1:8095,
   and waits for \`/api/v1/research/forex/status\`.
3. Downloads genuine Dukascopy EURUSD BID/ASK 1-minute quotes from
   **2024-03-01 through 2024-05-31** (includes the prior 45-day warmup).
4. Resamples to closed M15 quotes; rejects missing/unpaired BID/ASK or
   incomplete 15-minute buckets instead of fabricating prices/volume.
5. Paper-simulates LONG and SHORT with one USD 600 wallet and at most one
   position at a time over **2024-04-15 through 2024-05-31**.
6. Exports \`results/FOREX-EURUSD-LONG-SHORT-*.json\` and
   \`results/FOREX-EURUSD-LONG-SHORT-*-trades.csv\`.
7. Stops the backend it launched and leaves MySQL/data intact.

If another process owns port 8095, it stops without contacting that
server. Download or parsing failures fail closed; check
\`logs/forex-*.out.log\` and \`logs/forex-*.err.log\`.
The initial download can take several minutes; public API availability
and Dukascopy request limits are not guaranteed.

Review inputs without touching Docker/Maven/network:

~~~powershell
.\scripts\run-forex-eurusd-auto.ps1 -PrintOnly
~~~

Change only backtest dates and explicit research assumptions, for example:

~~~powershell
.\scripts\run-forex-eurusd-auto.ps1 -StartUtc '2024-06-17' -UntilUtc '2024-07-31'
~~~

This selects a 45-day earlier warmup; each run supports up to 54 days of
trading time and no more than 100 days of imported data. Fees, min units,
slippage and leverage in the script are **assumptions**, not broker terms.

### Latest LONG and SHORT research mapping

- LONG: confirmed rising swing pivots, EMA9/21 ascending, daily EMA21
  ascending on completed day quotes, 3–5 bar contracting downward pullback,
  bullish closed confirmation, structural stop under the bid swing.
  Entry restricted to weekdays; no BTC 0.45% stop-distance restriction.
- SHORT: symmetric confirmed falling pivots, EMA9/21 descending, completed
  daily EMA21 descending, 3–5 bar contracting upward pullback, bearish
  closed confirmation, structural stop above ask swing.
- Same wallet, USD risk-based size, intrabar stop-first and next-open
  scheduled trend-loss exits from held bar 30 to maximum 60. Position entries
  use ASK for LONG and BID for SHORT; exits reverse these quote sides.
- No Forex taker-buy/sell volume. The source BID/ASK candle files may include
  an internal provider volume field but it is **not tick count**; tick-volume
  proxy MIDAS is disabled by default and is NOT retrospectively substituted
  into the latest crypto Price Action + Volume filters.

IMPORTANT: This is a **new adaptation of the prior LONG hypothesis**, not
bit-for-bit reuse of the Binance LONG detector or confirmed profitable
Forex logic. Funding / overnight swap and the real broker's rollover session
are not yet modelled. Results are provisional research only.

---



**Scope:** spot EURUSD quotes, real bid/ask price history from a future broker or
independent historical vendor, paper-only backtesting in Java 25 / Spring Boot.
The crypto/Binance modules stay intact but their data sync and execution are
disabled when this research profile is used. **No live broker connector exists.**

A symbol switch from BTCUSDT to EURUSD is NOT enough. USD-M crypto futures
settlement, traded/base volume, taker-buy volume, exchange contract steps,
8-hour funding and Binance liquidation formulas cannot be applied directly
to OTC Forex. Real spot FX EUR/USD may have no consolidated market volume.

This PR begins with a separately labeled strategy:
\`FOREX_PA_V1_SHORT_PRICE_ONLY\`.

- Confirmed lower highs and lower lows (2 completed pivot bars to the right).
- Declining 15m EMA9/EMA21 plus negative EMA21 slope of at least 23 previously
  completed UTC day sessions (not the current forming day).
- 3–5-bar upward pullback with contracted candle bodies close to EMA9/21.
- Closed bearish confirmation, then SHORT entry at next **bid** open.
- Structural stop referenced to **ask** highs of the pullback, plus buffer.
- Exit to **ask** on stop (stop-first if ambiguous), or ask close on max hold.
- Risk/pip conversion in USD with base currency **units** (one standard lot is
  100,000 EUR; 1 pip = $10 per standard lot for EUR/USD), explicit commission,
  minimum/step size, slippage, leverage and daily risk.
- No long entries in this first experimental adaptation, matching the last
  research decision. A LONG module can be added later independently.

The optional \`tickVolumeMidasProxy=true\` requires valid positive **broker
tick-volume values** for every bar. It adds a causal swing-high anchored,
tick-volume-weighted trend-level check. This is **NOT** the old Binance
volume-based MIDAS retest: there is no standardized Forex trade volume or
taker-buy volume. Do not compare its results as if they came from the same
strategy. No values or volumes are made up when missing.

## 1. Where to put Forex market data

Place a *broker-exported, timestamp-normalized* UTF-8 CSV at

\`C:\Projetos\scalper-bot\data\forex\EURUSD-15m.csv\`

Exact header required:

~~~csv
open_time_utc,bid_open,bid_high,bid_low,bid_close,ask_open,ask_high,ask_low,ask_close,tick_volume
2024-01-08T00:00:00Z,1.09420,1.09446,1.09412,1.09435,1.09430,1.09456,1.09422,1.09445,157
2024-01-08T00:15:00Z,1.09435,1.09460,1.09425,1.09438,1.09445,1.09470,1.09435,1.09448,172
~~~

The two rows above show **format only**, not market history. Never use them
for a backtest. Import actual OHLC BID/ASK bars with UTC timezone, period
15 minutes, strictly ascending and aligned to UTC quarter hours.
\`tick_volume\` may be blank in every row for price-only research. If unavailable,
the tick-volume MIDAS proxy MUST remain off. An export of bid/mid candles
alone is insufficient: never fabricate ask prices by assuming a fixed spread.

The loader rejects crossed bid/ask opens/closes, invalid OHLC, repeated bars,
midweek missing 15-minute candles, zero/invalid prices, and unclosed or
misaligned timestamps. Friday-to-Sunday/Monday gaps up to 72 hours are
accepted as market-weekend closures, never interpolated. Holidays or daily
broker quote maintenance gaps require a source audit or a future explicit
session-calendar extension; do not silently treat missing quotes as bars.

Supported first-phase USD-quote pairs: EURUSD, GBPUSD, AUDUSD, NZDUSD.
JPY-cross or non-USD quote PnL requires currency conversion and is deliberately
rejected in this first step.

## 2. Run Spring in isolated Forex research profile

Terminal 1 in Windows PowerShell (Java 25 + Maven + Docker):

~~~powershell
cd C:\Projetos\scalper-bot
git switch feat/forex-eurusd-csv-research
git pull --ff-only

.\scripts\setup-forex-research-db.ps1
mvn clean test
if ($LASTEXITCODE -ne 0) { throw 'Java tests failed' }
.\scripts\start-forex-research.ps1
~~~

This creates a separate \`trading_bot_forex\` MySQL schema, preserving both
previous Binance testnet and Binance production historical research databases.
It leaves the legacy Binance Spring components idle, disables automatic Binance
data sync, WebSocket, AI, and **ALL** live order submission. The new Forex feed
reads ONLY local CSV bytes: no broker credentials are needed.

## 3. Execute an EURUSD paper backtest

Terminal 2, once the imported CSV covers the entire requested period and
includes at least 23 prior closed UTC sessions for warmup:

~~~powershell
cd C:\Projetos\scalper-bot

.\scripts\run-forex-eurusd-csv.ps1 -StartUtc '2024-02-01' -UntilUtc '2024-03-29' -PrintOnly

.\scripts\run-forex-eurusd-csv.ps1 -StartUtc '2024-02-01' -UntilUtc '2024-03-29' -CapitalUsd 600 -Leverage 10 -RiskPerTradePct 0.5 -DailyStopPct 3 -CommissionUsdPer100kPerSide 3.5 -SlippagePipsPerSide 0.2 -StopBufferPips 1 -MinUnits 1000 -UnitStep 1000 -MaxHoldingBars 60
~~~

All capital, costs, pip assumptions and positions in those commands are
**research hypotheses**, not broker prices or defaults for a live account.
Match to the eventual actual broker's commission, minimum trade size,
margin policy, spread conditions and permitted leverage BEFORE using the
results to make a decision.

The PowerShell command writes:
- \`results/forex-EURUSD-<start>-<end>-<utc-tag>.json\`
- \`results/forex-EURUSD-<start>-<end>-<utc-tag>-trades.csv\`

New API:
- \`GET /api/v1/research/forex/status\`
- \`POST /api/v1/research/forex/backtest\`

The API accepts only pair identifiers, financial parameters and UTC dates,
not a user-supplied filesystem path. One CSV dataset per pair configured
by \`FOREX_CSV_DIR\`, loaded through \`ForexHistoricalFeed\` so a subsequent
OANDA/IBKR/other broker candle adapter can be added without rewriting
the strategy engine.

## Limitations and next steps

- **No real trading or brokerage chosen.** Broker integration for data
  and orders is a separate task after agreeing on a licensed provider.
- No standardized institutional/composite FX volume exists in the file.
  Tick volume is not spot traded volume or directional aggressor volume.
- Swap/rollover fees, financing, margin calls, dynamic spreads, liquidity
  gaps and real broker execution/latency are NOT completely modelled.
  BID/ASK historical data are required; slippage is still an assumption.
- Forex follows 24/5 market sessions and UTC day boundaries are not the
  17:00 New York rollover cutoffs. We should implement a broker-specific
  session calendar and rollover model in the next phase.
- **FOREX_PA_V1 is a new deterministic price-action hypothesis**, not a
  "validated EURUSD port" of the BTC MIDAS/VPA backtests.
- Splitting training vs holdout and running non-overlapping windows must
  be designed BEFORE observing Forex results. One arbitrarily selected
  profitable period is not evidence of robustness.

Run the JUnit tests first; this PR has not run against a real exported Forex
dataset in this session. It is intended as a safely decoupled first migration.
