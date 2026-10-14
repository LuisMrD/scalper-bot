package br.com.tradingbot.simulation;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A causal approximation of the trading-range top third, not a Brooks
 * prescribed numerical formula. High/low are from 30 completed 15m bars.
 * Requires contiguous candles; does not inspect the next 5m fill.
 */
final class ClosedRangeLocation {
    static final int RANGE_BARS_15M = 30;
    private static final long BAR_MS = CandleInterval.M15.millis();
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    record Snapshot(long lastClosed15mTime, BigDecimal low30,
            BigDecimal high30, BigDecimal entrySignalClose, boolean upperThird) {}

    private final List<MarketCandle> candles;

    ClosedRangeLocation(String symbol, List<MarketCandle> recentDescending) {
        this.candles = new ArrayList<>(recentDescending);
        this.candles.sort(Comparator.comparingLong(MarketCandle::closeTime));
        if (candles.size() < RANGE_BARS_15M) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Missing 30 closed 15m candles for short range research");
        }
        for (var candle : candles) {
            if (!symbol.equals(candle.symbol()) || candle.interval() != CandleInterval.M15
                    || !candle.closed()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Expected only closed " + symbol + " 15m candles");
            }
        }
    }

    Snapshot at(long signalCloseTime, BigDecimal signalClosePrice) {
        return locate(signalCloseTime, signalClosePrice, false)
                .orElseThrow(() -> new IllegalStateException("Strict range lookup returned no result"));
    }

    /**
     * L2-only: a contiguous, flat 30-bar 15m range with ZERO trades
     * and ZERO volume is not a resistance/support setup. Return empty
     * rather than making a synthetic trade. Any gap, stale data, invalid
     * prices or a flat range WITH activity still rejects HTTP 422.
     */
    Optional<Snapshot> atIfTraded(long signalCloseTime, BigDecimal signalClosePrice) {
        return locate(signalCloseTime, signalClosePrice, true);
    }

    private Optional<Snapshot> locate(long signalCloseTime, BigDecimal signalClosePrice,
            boolean allowZeroActivityFlat) {
        if (signalClosePrice == null || signalClosePrice.signum() <= 0) {
            throw new IllegalArgumentException("Invalid signal price");
        }
        int low = 0, high = candles.size() - 1, last = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (candles.get(mid).closeTime() <= signalCloseTime) {
                last = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (last < RANGE_BARS_15M - 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Missing closed 15m range before SHORT signal " + signalCloseTime);
        }
        long lag = signalCloseTime - candles.get(last).closeTime();
        if (lag < 0 || lag >= BAR_MS) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "15m range is stale for SHORT signal " + signalCloseTime);
        }
        BigDecimal min = null, max = null;
        boolean zeroActivity = true;
        for (int i = last - RANGE_BARS_15M + 1; i <= last; i++) {
            var candle = candles.get(i);
            if (i > last - RANGE_BARS_15M + 1
                    && candle.openTime() - candles.get(i - 1).openTime() != BAR_MS) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "15m range has missing candles before signal " + signalCloseTime);
            }
            min = min == null ? candle.low() : min.min(candle.low());
            max = max == null ? candle.high() : max.max(candle.high());
            zeroActivity &= candle.baseVolume().signum() == 0
                    && candle.quoteVolume().signum() == 0
                    && candle.numberOfTrades() == 0
                    && candle.takerBuyBaseVolume().signum() == 0
                    && candle.takerBuyQuoteVolume().signum() == 0;
        }
        if (max.compareTo(min) <= 0) {
            if (max.compareTo(min) == 0 && allowZeroActivityFlat && zeroActivity) {
                return Optional.empty();
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No valid 15m trading range width at signal " + signalCloseTime
                    + (zeroActivity ? " (zero activity)" : " (nonzero trading activity)"));
        }
        // signal >= min + (max - min)*2/3; inclusive of exact boundary.
        boolean upper = signalClosePrice.subtract(min).multiply(THREE)
                .compareTo(max.subtract(min).multiply(TWO)) >= 0;
        return Optional.of(new Snapshot(candles.get(last).closeTime(), min, max,
                signalClosePrice, upper));
    }
}
