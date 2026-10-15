package br.com.tradingbot.simulation;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Closed 5m candles only. Standard Bollinger population stdev, SMA(20), 2 sigma.
 * Strict CLOSE beyond outer band, never wick-only or equality. No prediction.
 */
final class BollingerMeanReversion {
    static final int PERIOD = 20;
    static final BigDecimal DEVIATIONS = new BigDecimal("2");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    enum Side { LONG, SHORT }
    record Signal(Side side, long candleCloseTime, BigDecimal close,
                  BigDecimal lower, BigDecimal middle, BigDecimal upper) {}

    Signal detect(List<MarketCandle> closed5m) {
        if (closed5m.size() < PERIOD) return null;
        int n = closed5m.size();
        var last = closed5m.getLast();
        if (!"5m".equals(last.interval().code()) || !last.closed()
                || last.numberOfTrades() <= 0 || last.baseVolume().signum() <= 0
                || last.quoteVolume().signum() <= 0) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = n-PERIOD; i < n; i++) {
            var c = closed5m.get(i);
            if (!c.closed() || c.interval() != last.interval()
                    || c.closeTime() != last.closeTime()-(long)(n-1-i)*last.interval().millis()) {
                throw new IllegalArgumentException("Bollinger: missing or unclosed historical 5m bar");
            }
            // TESTNET can publish long stretches of stale OHLC with no trades.
            // No valid 20-bar volatility band can be inferred from inactive bars.
            if (c.numberOfTrades() <= 0 || c.baseVolume().signum() <= 0
                    || c.quoteVolume().signum() <= 0) return null;
            sum = sum.add(c.close());
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(PERIOD), MC);
        BigDecimal sq = BigDecimal.ZERO;
        for (int i=n-PERIOD; i<n; i++) {
            BigDecimal d=closed5m.get(i).close().subtract(mean);
            sq=sq.add(d.multiply(d, MC));
        }
        BigDecimal variance = sq.divide(BigDecimal.valueOf(PERIOD), MC);
        if (variance.signum() == 0) return null; // untradeable flat Testnet history
        BigDecimal std = variance.sqrt(MC);
        BigDecimal lower = mean.subtract(std.multiply(DEVIATIONS,MC));
        BigDecimal upper = mean.add(std.multiply(DEVIATIONS,MC));
        if (last.close().compareTo(lower) < 0)
            return new Signal(Side.LONG,last.closeTime(),last.close(),lower,mean,upper);
        if (last.close().compareTo(upper) > 0)
            return new Signal(Side.SHORT,last.closeTime(),last.close(),lower,mean,upper);
        return null;
    }
}
