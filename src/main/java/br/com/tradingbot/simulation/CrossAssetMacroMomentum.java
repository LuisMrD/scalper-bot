package br.com.tradingbot.simulation;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Causal rolling momentum on completed 15m candles, never an incomplete hourly bar.
 * 1h=4 bars; 4h=16 bars; 24h=96 bars. Return sign is not a market prediction.
 */
final class CrossAssetMacroMomentum {
    private static final int LOOKBACK_24H = 96;
    private static final long FIFTEEN_MINUTES = CandleInterval.M15.millis();
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    record Snapshot(long lastClosed15mTime, BigDecimal return1hPct,
            BigDecimal return4hPct, BigDecimal return24hPct) {}

    private final List<MarketCandle> candles;

    CrossAssetMacroMomentum(String symbol, List<MarketCandle> descendingCandles) {
        this.candles = new ArrayList<>(descendingCandles);
        this.candles.sort(Comparator.comparingLong(MarketCandle::closeTime));
        for (var bar : this.candles) {
            if (!bar.symbol().equals(symbol) || bar.interval() != CandleInterval.M15
                    || !bar.closed() || bar.close().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Invalid macro candle for " + symbol);
            }
        }
        if (candles.size() <= LOOKBACK_24H) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient 15m macro candles for 24h context: " + symbol);
        }
    }

    /** Shorter, pre-registered 3/6/9-hour horizons for LONG entry hypotheses. */
    record MagicSnapshot(long lastClosed15mTime, BigDecimal return3hPct,
            BigDecimal return6hPct, BigDecimal return9hPct) {
        BigDecimal returnHours(int hours) {
            return switch (MagicResearchNumbers.requireHours(hours)) {
                case 3 -> return3hPct;
                case 6 -> return6hPct;
                case 9 -> return9hPct;
                default -> throw new IllegalArgumentException("Allowed macro hours: 3/6/9");
            };
        }
    }

    MagicSnapshot atMagic(long signalCloseTime) {
        int last = verifiedIndex(signalCloseTime, 36);
        var current = candles.get(last);
        return new MagicSnapshot(current.closeTime(),
                pctChange(current.close(), candles.get(last - 12).close()),
                pctChange(current.close(), candles.get(last - 24).close()),
                pctChange(current.close(), candles.get(last - 36).close()));
    }

    Snapshot at(long signalCloseTime) {
        int last = verifiedIndex(signalCloseTime, LOOKBACK_24H);
        var current = candles.get(last);
        return new Snapshot(current.closeTime(),
                pctChange(current.close(), candles.get(last - 4).close()),
                pctChange(current.close(), candles.get(last - 16).close()),
                pctChange(current.close(), candles.get(last - LOOKBACK_24H).close()));
    }

    private int verifiedIndex(long signalCloseTime, int requiredBars) {
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
        if (last < requiredBars) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Missing macro lookback before SOL signal at " + signalCloseTime);
        }
        var current = candles.get(last);
        long lag = signalCloseTime - current.closeTime();
        if (lag < 0 || lag >= FIFTEEN_MINUTES) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "15m macro context is stale at SOL signal " + signalCloseTime);
        }
        // A gap in ANY required 15m candle invalidates the signal.
        for (int i = last - requiredBars + 1; i <= last; i++) {
            if (candles.get(i).openTime() - candles.get(i - 1).openTime()
                    != FIFTEEN_MINUTES) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Macro history contains an unfilled 15m gap at SOL signal "
                                + signalCloseTime);
            }
        }
        return last;
    }

    private static BigDecimal pctChange(BigDecimal current, BigDecimal previous) {
        return current.subtract(previous).multiply(ONE_HUNDRED)
                .divide(previous, MC);
    }
}
