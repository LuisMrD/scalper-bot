package br.com.tradingbot.simulation;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Research-only, fully causal approximation of Brooks's failed breakout
 * followed by two downward attempts (L2); NOT a claim of exact Brooks L2.
 *
 * Chronology:
 * - f: 5m bar wicks ABOVE resistance of the 30 CLOSED 15m bars prior to f,
 *      but closes back below the pre-existing resistance;
 * - k: bearish first down attempt 1..3 CLOSED 5m bars after f;
 * - m: an intervening completed bullish rebound, lower than failed-break high;
 * - now: the second bearish attempt closes under k.low and previous bar.low,
 *      before 9 primary bars have elapsed from failure.
 *
 * Never reads the next 5m candle or a forming 15m bar. Failed breakout must
 * have a fixed PRE-event resistance, not a range polluted by the spike.
 */
final class FailedBreakoutL2Detector {
    static final int MAX_AGE_5M = 9;
    static final int FIRST_ATTEMPT_WITHIN_5M = 3;
    private static final long FIVE_MINUTES = CandleInterval.M5.millis();
    private static final BigDecimal THREE = BigDecimal.valueOf(3);

    record Signal(long failureCloseTime, long firstAttemptCloseTime,
            long reboundCloseTime, long secondAttemptCloseTime,
            BigDecimal priorResistance, BigDecimal priorSupport) {}

    private final ClosedRangeLocation ranges;

    FailedBreakoutL2Detector(ClosedRangeLocation ranges) {
        this.ranges = Objects.requireNonNull(ranges);
    }

    /** Primary bars must be chronologically sorted and already closed. */
    Signal detect(List<MarketCandle> completed5m) {
        if (completed5m == null || completed5m.size() < 5) return null;
        int lastIndex = completed5m.size() - 1;
        var second = completed5m.get(lastIndex);
        if (!second.closed() || second.interval() != CandleInterval.M5
                || second.close().compareTo(second.open()) >= 0
                || second.close().compareTo(completed5m.get(lastIndex - 1).low()) >= 0)
            return null;

        int from = Math.max(0, lastIndex - MAX_AGE_5M);
        for (int f = lastIndex - 4; f >= from; f--) {
            var failed = completed5m.get(f);
            if (!failed.closed() || failed.interval() != CandleInterval.M5) continue;
            boolean continuous = true;
            for (int i = f + 1; i <= lastIndex; i++) {
                var prev = completed5m.get(i - 1);
                var current = completed5m.get(i);
                if (!current.closed() || current.interval() != CandleInterval.M5
                        || current.openTime() - prev.openTime() != FIVE_MINUTES) {
                    continuous = false;
                    break;
                }
            }
            if (!continuous) continue;

            ClosedRangeLocation.Snapshot before;
            try {
                before = ranges.at(failed.openTime() - 1L, failed.high());
            } catch (org.springframework.web.server.ResponseStatusException missing) {
                // Missing historical range is a DATA INTEGRITY problem, not a
                // negative signal that should silently bias the sample.
                throw missing;
            }
            var ceiling = before.high30();
            var floor = before.low30();
            if (failed.high().compareTo(ceiling) <= 0
                    || failed.close().compareTo(ceiling) >= 0
                    || failed.close().compareTo(floor) <= 0)
                continue;
            // No repeated breakout above the failure high during confirmation.
            boolean invalidated = false;
            for (int i = f + 1; i <= lastIndex; i++) {
                if (completed5m.get(i).high().compareTo(failed.high()) > 0) {
                    invalidated = true;
                    break;
                }
            }
            if (invalidated) continue;
            // Reject a second entry already at the bottom third of the range.
            if (second.close().subtract(floor).multiply(THREE)
                    .compareTo(ceiling.subtract(floor)) < 0)
                continue;
            int maxFirst = Math.min(f + FIRST_ATTEMPT_WITHIN_5M, lastIndex - 2);
            for (int k = f + 1; k <= maxFirst; k++) {
                var first = completed5m.get(k);
                if (first.close().compareTo(first.open()) >= 0
                        || first.close().compareTo(failed.close()) >= 0) continue;
                if (second.close().compareTo(first.low()) >= 0) continue;
                for (int m = k + 1; m < lastIndex; m++) {
                    var rebound = completed5m.get(m);
                    if (rebound.close().compareTo(rebound.open()) > 0
                            && rebound.close().compareTo(first.close()) > 0
                            && rebound.high().compareTo(failed.high()) < 0) {
                        return new Signal(failed.closeTime(), first.closeTime(),
                                rebound.closeTime(), second.closeTime(), ceiling, floor);
                    }
                }
            }
        }
        return null;
    }
}
