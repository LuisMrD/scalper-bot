# BTC MIDAS: prospective OOS, 3 frozen policies, one shared paper account

**Research only, no real orders.** The source is the same 15m BTCUSDT mixed
pullback detector (LONG baseline, SHORT anchored MIDAS retest) and fully closed
1d EMA21 trend as the October 17 in-sample experiment. The original hard
structural stop, stop-first OHLC convention, confirmed-pivot trailing, execution
on next 15m open and `DYNAMIC_TREND_60` remain unchanged. No GPT/LLM is used.

## Why this is a NEW replay

The 12 × 900h simulations through **2026-10-15 23:59:59.999 UTC** selected the
filters. They are **in-sample**. Summing the LONG/SHORT CSV profits from those
independent books is NOT an equivalent shared-equity trading system. This study
replays each signal candle against THREE books, each with its own initial US$600,
while each book has exactly ONE cash wallet, one daily loss budget and at most
one open position across both directions. The accounts never share capital
with one another.

| Book | LONG gate | SHORT gate |
|---|---|---|
| `CONTROL` | `BASELINE` | `BASELINE` |
| `CANDIDATE_A` | `WEEKDAYS_ONLY` | `COMBINED_045` |
| `CANDIDATE_B` | `WEEKDAYS_ONLY` | `COMBINED_050` |

**Frozen before seeing prospective data:** 0.5% risk per trade, 3% daily
realized-loss stop (shared), leverage 1×, 0.05% commission each side,
0.02% slippage each side, 0.01% spread, funding *assumed* 0, maintenance
margin assumption 0.5%, 50% maximum cost-to-risk, original Price Action and
Volume parameters, initial capital US$600, 60 signal-context bars and 1000
causal MIDAS warmup bars. The API validates these parameters and refuses
attempts to change them. Exchange tick/lot/notional filters come from
TESTNET exchangeInfo, never guessed.

**One position at a time**: both directions can emit a confirmed signal on the
same closed 15m candle; each is checked against its direction-specific filter.
If both remain pending, `LONG` is tried first on the next open (documented
deterministic tie-break); `SHORT` is blocked if the long position opened.
Failed LONG sizing/entry lets SHORT try. If ANY position was open at
the start of the bar, a later intrabar stop or an exit on the open does NOT
unlock a second fill retroactively at that same bar open; new entries wait
until a subsequent signal and next open. A portfolio does not hedge itself
or run two simultaneous positions.

The weekend filter checks NEXT open UTC; the stop filter checks
`abs(signalReference - originalStructuralStop) / signalReference * 100`.
There is NO access to next-bar prices or future outcomes at signal time.
The underlying protective stop is not tightened to 0.45% or 0.50%;
those values are only entry eligibility limits.

## Temporal safety

The strict OOS boundary is **2026-10-16 00:00:00.000 UTC**. Every decision
candle must start on or after that instant. Up to 1000 earlier historical
candles may only feed indicators; they do not issue trading decisions.
Each full window spans **3600 × 15 minutes = 900 hours**.

Thus the earliest full window ends **2026-11-22 11:59:59.999 UTC**. Before
that, the backend correctly returns HTTP 400 instead of presenting in-sample
numbers as prospective evidence. Additional non-overlapping 900h windows
can be requested later, when complete market data exist. No future candles
are fabricated. Data gaps/unclosed bars return HTTP 422.

## Independent historical holdout: 2023-2024 (run now)

This is a **different** evaluation from both the previously inspected 2025-26
in-sample 12 windows and the prospective post-2026-10-15 windows. Candidate A,
B and CONTROL remain FROZEN. The historical test only counts as independent
if 2023-24 did **not** influence any prior indicator, regime, filter,
parameter or candidate-selection decision. Check the research history first;
if that period was seen, select a genuinely untouched dataset and implement
a separate frozen-bound evaluation rather than pretending it is new.

The new \`POST /api/v1/research/independent-setups/shared-oos/backtest-historical-holdout\`
rejects any 900h replay not entirely in **2023-01-01 through
2024-12-31 UTC**. It is tagged \`PRE_FROZEN_HISTORICAL_HOLDOUT\`, NEVER
\`STRICT_PROSPECTIVE_OOS\`. Three frozen shared cash books execute chronologically
from closed candles, each with USD 600 reset per window.

Research candles must be public Binance USD-M Futures PRODUCTION (not testnet),
stored in a completely different MySQL schema from current testnet candles:
\`trading_bot_research\`. Research starts with trading orders and AI off.
A historical replay does not request orders or use future candles.

### Terminal 1: create separate database, start the Spring app

Stop the existing testnet Spring process on port 8080 first, but leave the
Docker MySQL container running. On Windows PowerShell:

~~~powershell
cd C:\Projetos\scalper-bot
git switch feat/btc-midas-shared-oos-validation
git pull --ff-only
.\scripts\setup-btc-historical-research-db.ps1
mvn clean test
# Keep this terminal open after the application starts:
.\scripts\start-btc-historical-research.ps1
~~~

The database setup creates \`trading_bot_research\`, grants access only for
the repository's existing local \`trading\` account, and does not delete or
overwrite \`trading_bot\`. Startup explicitly uses Spring profiles
\`backtest,research\` and \`RESEARCH_DB_URL\`; it cannot silently reuse the
existing Testnet research data because the research URL is hard-coded to a
different local schema.

### Terminal 2: synchronize public BTC history

Load at least the 1,000 × 15m MIDAS warmup before the first evaluation
and at least 60 complete daily reference candles. July 2023 through
December 2024 provides margin on both. Synchronization is idempotent:

~~~powershell
cd C:\Projetos\scalper-bot
.\scripts\tests\test-btc-15m-shared-oos-syntax.ps1
.\scripts\sync-independent-daily-weekly.ps1 -Symbols BTCUSDT -Intervals 15m,1d -FromUtc 2023-07-01 -UntilUtc 2024-12-31 -PrintOnly
.\scripts\sync-independent-daily-weekly.ps1 -Symbols BTCUSDT -Intervals 15m,1d -FromUtc 2023-07-01 -UntilUtc 2024-12-31 -FailOnEmptyDaily
~~~

The public Binance endpoint must actually return complete continuous
historical data. If it does not, stop and obtain/verifiably import official
archived production klines separately; never fabricate missing 15m bars.
Historical contract tick size, lot size, fees and liquidity can differ from
today's metadata; the runner currently uses the exchange's present public
metadata, a limitation to disclose when interpreting the holdout.

### Terminal 2: run the three frozen shared-wallet books

~~~powershell
.\scripts\run-btc-15m-shared-oos.ps1 -HistoricalHoldout -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1735689599999 -Windows 12 -PrintOnly
.\scripts\run-btc-15m-shared-oos.ps1 -HistoricalHoldout -Source production -ConfirmIsolatedDatabase -AsOfEpochMs 1735689599999 -Windows 12
~~~

Start with \`-Windows 1\` if you first want to verify that the latest
900-hour period has complete data; then run 12 full disjoint windows.
Outputs are in \`results/shared-*-BTC-MIDAS-SHARED-PRE_FROZEN_HISTORICAL_HOLDOUT-...\`.
No new profits are implied by the capability to run this experiment. Compare
performance by window, drawdown, number of trades, top-winner concentration
and both side contributions. Research on true real funding remains a future
addition; this model STILL ASSUMES ZERO funding.

---

## Re-run the ORIGINAL historical window cohort immediately

The `-InSample` switch is an explicitly labeled **historical diagnostic**,
NOT a prospective performance estimate. It invokes the same chronological
shared-wallet engine, with separate route
`POST /api/v1/research/independent-setups/shared-oos/backtest-insample`.
That route accepts **ONLY** the twelve original disjoint 900h periods ending
in the last 15m close at `2026-10-15T23:59:59.999Z`. It rejects arbitrary
windows, changed frozen model parameters and prospective dates. Capital
resets to US$600 for each policy at each window.

~~~powershell
cd C:\Projetos\scalper-bot
git switch feat/btc-midas-shared-oos-validation
git pull --ff-only
mvn clean test
# Terminal 1 (keep open):
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600 -MaxCostToInitialRiskPct 50

# Terminal 2:
.\scripts\tests\test-btc-15m-shared-oos-syntax.ps1
.\scripts\run-btc-15m-shared-oos.ps1 -InSample -AsOfEpochMs 1792108799999 -Windows 12
~~~

Outputs carry `HISTORICAL_INSAMPLE_ONLY` in their filenames and execution
model; they are never combined with real OOS results. Compare candidate
profits and drawdowns against the re-run **shared-wallet CONTROL**, not
the sum of old independent LONG/SHORT accounts.

## API

- `GET /api/v1/research/independent-setups/shared-oos/status`: locked policy
  mapping, risk and date metadata.
- `POST /api/v1/research/independent-setups/shared-oos/backtest`: receives the
  existing `IndependentResearchRequest`, returns `sharedPortfolios` for
  CONTROL, CANDIDATE_A and CANDIDATE_B, with directional trade records,
  per-policy drawdown and per-candle decision diagnostics.
- The new API is **read-only for market data** and does not inject or call
  a broker order service, OpenAI, or the research JDBC writer.

## Windows PowerShell

~~~powershell
cd C:\Projetos\scalper-bot
git fetch origin
git switch feat/btc-midas-shared-oos-validation
git pull --ff-only

# Terminal 1: existing paper-only local backend, TESTNET research DB
.\scripts\start-backtest.ps1 -IncludeDailyWeekly -Symbols BTCUSDT -CapitalUsdt 600

# Terminal 2: policy/period preview, runs without API calls
.\scripts\run-btc-15m-shared-oos.ps1 -PrintOnly

# Only when a FULL 900h OOS period is available in MySQL (Nov 22 or later):
.\scripts\run-btc-15m-shared-oos.ps1 -AsOfEpochMs 1795348799999 -Windows 1
~~~

The runner checks the server is paper-only, validates local TESTNET source
and contract filters, rejects in-sample windows, and exports:

- `results/shared-windows-BTC-MIDAS-SHARED-OOS-*.csv`
- `results/shared-trades-BTC-MIDAS-SHARED-OOS-*.csv`
- `results/shared-events-BTC-MIDAS-SHARED-OOS-*.csv`
- `results/shared-config-BTC-MIDAS-SHARED-OOS-*.json`

All closed positions carry a side. For windows ending with an OPEN position,
`finalMarkedEquity` is mark-to-market; `realizedNetUsdt` is cash minus
initial capital and includes entry fees already paid. Do not confuse them.
For multiple windows, capital is reset per policy/window; the sum of PnLs
is NOT a compounded continuous portfolio.

## Acceptance criteria before interpreting performance

1. Run `mvn clean test` with **Java 25**; test frozen parameter rejection,
   strict temporal separation and a single occupied wallet.
2. Cross-check CONTROL LONG and SHORT signal/entry/exit logic against the
   October 17 baseline for comparable policy settings; shared exposure
   deliberately changes the actual entry sequence and sizing, so PnL need
   not reproduce the **sum** of isolated books.
3. Compare realized/marked net PnL, trade count, win rate, net profit factor,
   fees, funding assumptions, joint max drawdown and results across windows.
4. Audit directional contribution, two largest winning trades and results
   after removing their contribution **only as a concentration diagnostic**,
   not by claiming the filtered list constitutes a new backtest.
5. Refrain from tuning filters on the OOS results without declaring a NEW
   later holdout; otherwise the validation data becomes in-sample.

**Limitations:** Funding is still assumed zero, not the true Binance funding
rate. Liquidation behavior is an approximation inherited from the paper
engine. Intrabar stop-first ordering is conservative for ambiguous candles
but does not reconstruct tick data. No profitability claim is made before
genuine prospective results.
