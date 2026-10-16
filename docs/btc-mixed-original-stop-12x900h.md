# BTCUSDT mixed LONG/SHORT, original stop — $600, 12 x 900 hours

This is a SEPARATE, paper-only study. No real orders, live execution, paid AI,
or automatic merge. Prior 6x600h reports and exits remain available.

## Frozen configuration

- Symbol: BTCUSDT, Binance USD-M Futures TESTNET persisted in local MySQL.
- Primary entry: completed 15m; higher timeframe: last completed daily 1d
  EMA21 slope direction. No unfinished daily candles.
- ONLY two existing setups: TREND_PULLBACK_MIXED (LONG) and
  TREND_PULLBACK_MIXED_SHORT (SHORT), not the strict variants.
- ONLY the original BASELINE exit: hard structural stop with tick buffer,
  confirmed-pivot structural trailing after 1R, price-action invalidation,
  stop-first OHLC model, max 30 candles = 7.5 hours. Neither simple nor
  structural PA-volume reversal, ATR buffers nor alternative trailing are used.
- Initial bankroll: $600 per paper account per window, risk per trade 0.5%,
  daily stop 3%, leverage 1; fee 0.05% per side, slippage 0.02% per side,
  spread 0.01%, hypothetical funding 0. All other numerical technical and
  financial parameters are copied from the prior 15m study unchanged.
- 12 disjoint contiguous windows x 900 hours each = 3600 closed 15m candles
  per window, 10800 hours = 450 days total. First 15m bar opens at
  2025-07-23T00:00:00Z; final close 2026-10-15T23:59:59.999Z.
- 24 independent paper portfolios: 12 windows x 2 setups. Each window
  RESETs its own cash and positions; summing accounts is NOT one continuously
  invested portfolio. Final candle cannot create an unfillable new entry.
- As this time span overlaps previously studied periods, it is not an
  unbiased fully out-of-sample study; results require separate validation.

## Historical data / fail-closed rules

Earlier tests only needed approximately 150 days of 15m BTC. The new
study requires 450 days plus 60 days of completed 1d context, and
60 closed 15m warmup candles. The optional historical sync requests
dates back through May 2025; TESTNET retention for such old candles
is NOT guaranteed. Do NOT automatically substitute Binance production
candles, interpolate gaps or fabricate missing data.

An additional optional asOf parameter on the existing read-only
GET /api/v1/market-data/candles endpoint allows checking exactly the
60 daily candles BEFORE the oldest 15m decision instead of just reading
the most recent 500. The replay engine checks the entire requested
15m sequence and daily higher context for missing/unclosed candles.

## Windows PowerShell

Update the GitHub branch and restart the local backend in terminal 1:

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/independent-trend-pullback-range-breakout-5m
git pull --ff-only
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600
~~~

In terminal 2:

~~~powershell
# Only print 12 window dates / 24 books (no network, no MySQL)
.\scripts\run-btc-15m-mixed-baseline-12x900h.ps1 -AsOfDateUtc 2026-10-15 -PrintOnly

# Run if history already exists fully in MySQL
.\scripts\run-btc-15m-mixed-baseline-12x900h.ps1 -AsOfDateUtc 2026-10-15

# Otherwise attempt to sync BTC 15m/1d from historical TESTNET only
.\scripts\run-btc-15m-mixed-baseline-12x900h.ps1 -AsOfDateUtc 2026-10-15 -SyncHistory
~~~

Dedicated paper endpoint:
POST /api/v1/research/independent-setups/backtest-mixed-baseline-900h
requires BTCUSDT, 15m/1d, 3600 bars, 60 warmup, $600.
Read-only safety/status endpoint:
GET /api/v1/research/independent-setups/mixed-baseline-900h/status

The runner exports six CSV/JSON files into results/ with the unique
BTC-mixed-baseline-15m-1d-900h prefix: windows, aggregate, trades,
diagnostics, rejections and config. 2 arms x 12 windows x 3599 completed
entry-decision bars = 86,376 entry observations.

When comparing to the old $1500 sample, bear in mind $600 leverage 1
and testnet lot size/min notional may block MORE otherwise valid signals.
The 12 accounts per direction reset capital; results do not show 450 days
of continuous compounding. Funding zero remains an explicit hypothesis.
