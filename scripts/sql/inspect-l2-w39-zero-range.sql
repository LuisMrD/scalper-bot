-- READ-ONLY forensic audit, SOLUSDT Futures TESTNET, research window 39.
-- Error: 422 RANGE_FALSE_BREAK_L2 because 30 completed 15m bars
-- had max(high_price) = min(low_price) at 2025-06-18 02:59:59 UTC.
-- Run against the same local MySQL trading_bot schema as Spring Boot.
-- Do NOT overwrite/delete/update market data or relax backtest validation.
USE trading_bot;

-- A. Exactly the most recent 30 CLOSED 15m candles at the failed signal.
-- If width = 0, *all 30 candles* share a single price level.
WITH last30 AS (
    SELECT c.open_time_epoch_ms, c.close_time_epoch_ms,
           c.open_price, c.high_price, c.low_price, c.close_price,
           c.base_volume, c.number_of_trades
    FROM candles c JOIN instruments i ON i.id = c.instrument_id
    WHERE i.symbol = 'SOLUSDT' AND c.timeframe = '15m'
      AND c.closed = TRUE AND c.close_time_epoch_ms <= 1750215599999
    ORDER BY c.open_time_epoch_ms DESC
    LIMIT 30
)
SELECT COUNT(*) AS bars_15m,
       MIN(open_time_epoch_ms) AS first_open_ms,
       MAX(close_time_epoch_ms) AS last_close_ms,
       MIN(low_price) AS min_low_30,
       MAX(high_price) AS max_high_30,
       MAX(high_price) - MIN(low_price) AS width_30,
       SUM(CASE WHEN high_price = low_price THEN 1 ELSE 0 END) AS zero_spread_bars,
       COUNT(DISTINCT close_price) AS distinct_close_prices,
       SUM(base_volume) AS total_base_volume,
       SUM(number_of_trades) AS total_trades
FROM last30;

-- B. Inspect individual bars around failure, in chronological order.
WITH last30 AS (
    SELECT c.open_time_epoch_ms, c.close_time_epoch_ms,
           c.open_price, c.high_price, c.low_price, c.close_price,
           c.base_volume, c.number_of_trades
    FROM candles c JOIN instruments i ON i.id = c.instrument_id
    WHERE i.symbol = 'SOLUSDT' AND c.timeframe = '15m'
      AND c.closed = TRUE AND c.close_time_epoch_ms <= 1750215599999
    ORDER BY c.open_time_epoch_ms DESC
    LIMIT 30
)
SELECT FROM_UNIXTIME(open_time_epoch_ms / 1000.0) AS open_time_db_timezone,
       open_time_epoch_ms, open_price, high_price, low_price, close_price,
       base_volume, number_of_trades
FROM last30
ORDER BY open_time_epoch_ms ASC;

-- C. Cross-check independently persisted CLOSED 5m bars over the
-- SAME time interval as the 30 15m bars. Expect 90 contiguous 5m bars.
-- If 5m has a price range > 0 but 15m width = 0, candle datasets disagree.
WITH last30 AS (
    SELECT c.open_time_epoch_ms, c.close_time_epoch_ms
    FROM candles c JOIN instruments i ON i.id = c.instrument_id
    WHERE i.symbol = 'SOLUSDT' AND c.timeframe = '15m'
      AND c.closed = TRUE AND c.close_time_epoch_ms <= 1750215599999
    ORDER BY c.open_time_epoch_ms DESC
    LIMIT 30
),
bounds AS (
    SELECT MIN(open_time_epoch_ms) AS first_open,
           MAX(close_time_epoch_ms) AS last_close FROM last30
)
SELECT c.timeframe, COUNT(*) AS bars_5m_expected_90,
       MIN(c.open_time_epoch_ms) AS first_open_ms,
       MAX(c.close_time_epoch_ms) AS last_close_ms,
       MIN(c.low_price) AS min_low_5m,
       MAX(c.high_price) AS max_high_5m,
       MAX(c.high_price) - MIN(c.low_price) AS width_5m,
       SUM(CASE WHEN c.high_price = c.low_price THEN 1 ELSE 0 END)
           AS zero_spread_bars,
       SUM(c.base_volume) AS total_base_volume,
       SUM(c.number_of_trades) AS total_trades
FROM candles c JOIN instruments i ON i.id = c.instrument_id
CROSS JOIN bounds b
WHERE i.symbol = 'SOLUSDT' AND c.timeframe = '5m'
  AND c.closed = TRUE
  AND c.open_time_epoch_ms >= b.first_open
  AND c.close_time_epoch_ms <= b.last_close
GROUP BY c.timeframe;

-- D. Check whether this is an isolated flat segment or a longer Testnet
-- data-quality issue within +/- three days of the signal. Descriptive only.
SELECT c.timeframe, COUNT(*) AS bars,
       SUM(CASE WHEN c.high_price = c.low_price THEN 1 ELSE 0 END)
           AS zero_spread_bars,
       MIN(c.low_price) AS min_low, MAX(c.high_price) AS max_high,
       SUM(c.base_volume) AS base_volume,
       SUM(c.number_of_trades) AS number_of_trades
FROM candles c JOIN instruments i ON i.id = c.instrument_id
WHERE i.symbol = 'SOLUSDT' AND c.timeframe IN ('5m', '15m')
  AND c.closed = TRUE
  AND c.close_time_epoch_ms BETWEEN
      1750215599999 - 3 * 86400000 AND 1750215599999 + 3 * 86400000
GROUP BY c.timeframe
ORDER BY c.timeframe;
