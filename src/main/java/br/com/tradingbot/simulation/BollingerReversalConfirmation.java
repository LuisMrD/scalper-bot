package br.com.tradingbot.simulation;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Causal two-step Bollinger reversal on completely CLOSED 5m or 1d bars.
 *
 * 1) A strict close outside the outer band ARMS a candidate; never enters.
 * 2) One of three opposite-direction Price Action responses confirms it:
 *    hammer/shooting star, a strong reversal candle, or a close past the
 *    observed candidate extreme. A separate inside-band close and prior-candle
 *    microstructure breakout are NOT required (filters 1 and 2 rolled back).
 * Entries may only be proposed at the confirming candle CLOSE, filled at T+1.
 *
 * Historical Testnet candles with no actual trades invalidate an active setup.
 */
final class BollingerReversalConfirmation {
    static final int MAX_WAIT_CANDLES = 9;
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal SIX_TENTHS = new BigDecimal("0.6");
    private final BollingerMeanReversion bands = new BollingerMeanReversion();
    enum Pattern { HAMMER_OR_SHOOTING_STAR, STRONG_REVERSAL_CANDLE, CLOSE_BREAKS_PRIOR_EXTREME }
    record Confirmation(BollingerMeanReversion.Signal trigger, long confirmedAtCloseTime,
                        Pattern pattern, BigDecimal extremeLow, BigDecimal extremeHigh) {}
    record Step(boolean armed, boolean expired, boolean invalidated,
                Confirmation confirmed) {}

    private BollingerMeanReversion.Signal pending;
    private int waited;
    private BigDecimal highBefore;
    private BigDecimal lowBefore;
    private long lastProcessedClose = -1;

    void cancel() {
        pending = null;
        waited = 0;
        highBefore = null;
        lowBefore = null;
    }

    /** Other entry or open position: advance causal clock without arming a setup. */
    void skipClosedBar(MarketCandle current) {
        checkAndAdvance(current);
        cancel();
    }

    private void checkAndAdvance(MarketCandle current) {
        if (!current.closed() || !("5m".equals(current.interval().code()) || "1d".equals(current.interval().code()))) {
            throw new IllegalArgumentException("Bollinger confirmation requires a closed 5m or 1d bar");
        }
        if (current.closeTime() <= lastProcessedClose) {
            throw new IllegalArgumentException("Bollinger bars must advance monotonically");
        }
        if (lastProcessedClose >= 0
                && current.closeTime() != lastProcessedClose + current.interval().millis()) {
            throw new IllegalArgumentException("Bollinger candidate contains a missing bar");
        }
        lastProcessedClose = current.closeTime();
    }

    Step onClosedBar(List<MarketCandle> completed5m) {
        Objects.requireNonNull(completed5m, "completed5m");
        if (completed5m.isEmpty()) return new Step(false,false,false,null);
        MarketCandle current = completed5m.getLast();
        checkAndAdvance(current);

        if (pending == null) {
            var signal = bands.detect(completed5m);
            if (signal == null) return new Step(false,false,false,null);
            pending = signal;
            waited = 0;
            highBefore = signal.triggerHigh();
            lowBefore = signal.triggerLow();
            // Never confirm on the SAME bar that first closed outside.
            return new Step(true,false,false,null);
        }

        waited++;
        if (waited > MAX_WAIT_CANDLES) {
            cancel();
            return new Step(false,true,false,null);
        }
        boolean traded = current.numberOfTrades() > 0
                && current.baseVolume().signum() > 0
                && current.quoteVolume().signum() > 0;
        boolean longSide = pending.side() == BollingerMeanReversion.Side.LONG;
        // Once the middle is already touched while awaiting confirmation,
        // the signal-time mean-reversion opportunity has elapsed.
        boolean alreadyAtTarget = longSide
                ? current.high().compareTo(pending.middle()) >= 0
                : current.low().compareTo(pending.middle()) <= 0;
        if (!traded || alreadyAtTarget) {
            cancel();
            return new Step(false,false,true,null);
        }
        MarketCandle previous = completed5m.size() >= 2
                ? completed5m.get(completed5m.size()-2) : null;
        if (previous == null || previous.closeTime() + current.interval().millis()
                != current.closeTime()) {
            throw new IllegalArgumentException("Missing previous candle for Price Action");
        }
        // Persist each observed extreme even when the candle fails to confirm.
        // Classifier compares against PREVIOUS extremes, before adding current.
        BigDecimal priorHigh = highBefore;
        BigDecimal priorLow = lowBefore;
        highBefore = highBefore.max(current.high());
        lowBefore = lowBefore.min(current.low());
        var pattern = classify(current, previous, pending.side(), priorHigh, priorLow);
        if (pattern == null) return new Step(false,false,false,null);
        var confirmed = new Confirmation(pending, current.closeTime(), pattern,
                lowBefore, highBefore);
        cancel();
        return new Step(false,false,false,confirmed);
    }

    private static Pattern classify(MarketCandle c, MarketCandle previous,
            BollingerMeanReversion.Side side, BigDecimal priorHigh, BigDecimal priorLow) {
        boolean isLong = side == BollingerMeanReversion.Side.LONG;
        BigDecimal body = c.close().subtract(c.open()).abs();
        BigDecimal range = c.high().subtract(c.low());
        if (body.signum() == 0 || range.signum() <= 0) return null;
        boolean reversalColor = isLong
                ? c.close().compareTo(c.open()) > 0 : c.close().compareTo(c.open()) < 0;
        if (!reversalColor) return null;
        BigDecimal upperWick = c.high().subtract(c.open().max(c.close()));
        BigDecimal lowerWick = c.open().min(c.close()).subtract(c.low());
        boolean pinBar = isLong
                ? lowerWick.compareTo(body.multiply(TWO)) >= 0
                    && upperWick.compareTo(body) <= 0
                : upperWick.compareTo(body.multiply(TWO)) >= 0
                    && lowerWick.compareTo(body) <= 0;
        if (pinBar) return Pattern.HAMMER_OR_SHOOTING_STAR;

        boolean strongBody = body.compareTo(range.multiply(SIX_TENTHS)) >= 0
                && (isLong ? c.close().compareTo(previous.close()) > 0
                           : c.close().compareTo(previous.close()) < 0);
        if (strongBody) return Pattern.STRONG_REVERSAL_CANDLE;

        boolean strictBreak = isLong ? c.close().compareTo(priorHigh) > 0
                : c.close().compareTo(priorLow) < 0;
        if (strictBreak) return Pattern.CLOSE_BREAKS_PRIOR_EXTREME;
        return null;
    }
}
