package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Research-only VPA hypothesis using a CLOSED candle sequence from Bollinger
 * outside-close to confirmation. This is an operational interpretation of
 * effort-vs-result, NOT a rule attributed verbatim to Anna Coulling.
 *
 * The 20 bars preceding the trigger are reference bars only, never signals.
 * No future candle, last-candle taker dominance or open order is required.
 */
final class BollingerSequenceVpa {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int BASELINE = 20;
    private static final BigDecimal SPIKE = new BigDecimal("1.25");
    private static final BigDecimal NARROW = new BigDecimal("0.85");
    private static final BigDecimal WICK = new BigDecimal("0.25");
    private static final BigDecimal FADE_VOLUME = new BigDecimal("0.85");
    private static final BigDecimal FADE_SPREAD = new BigDecimal("0.90");
    private static final BigDecimal FAVORABLE_CLOSE = new BigDecimal("0.60");

    record Evidence(boolean approved, String rejection, int sequenceCandles,
            BigDecimal triggerRelativeVolume, BigDecimal triggerRelativeSpread,
            BigDecimal confirmationRelativeVolume, boolean triggerAbsorption,
            boolean continuationPressureFaded, boolean failedContinuation,
            boolean confirmationRejection, boolean strongContinuation) {}

    Evidence assess(List<MarketCandle> tape,
            BollingerReversalConfirmation.Confirmation signal,
            VolumeSnapshot.Observation lastObservation) {
        if (tape.isEmpty() || tape.getLast().closeTime() != signal.confirmedAtCloseTime())
            throw new IllegalArgumentException("VPA signal must match latest CLOSED bar");
        int triggerIndex = -1;
        for (int i = tape.size() - 2; i >= 0; i--) {
            if (tape.get(i).closeTime() == signal.trigger().candleCloseTime()) {
                triggerIndex = i;
                break;
            }
        }
        if (triggerIndex < BASELINE || tape.size() - triggerIndex < 2)
            return insufficient("VPA_SEQUENCE_INSUFFICIENT_HISTORY");

        MarketCandle trigger = tape.get(triggerIndex);
        MarketCandle confirmation = tape.getLast();
        BigDecimal quoteSum = BigDecimal.ZERO;
        BigDecimal spreadSum = BigDecimal.ZERO;
        for (int i = triggerIndex - BASELINE; i < triggerIndex; i++) {
            MarketCandle c = tape.get(i);
            if (!valid(c)) return insufficient("VPA_SEQUENCE_INVALID_MARKET_DATA");
            quoteSum = quoteSum.add(c.quoteVolume());
            spreadSum = spreadSum.add(span(c));
        }
        if (quoteSum.signum() <= 0 || spreadSum.signum() <= 0)
            return insufficient("VPA_SEQUENCE_INVALID_MARKET_DATA");
        BigDecimal meanQuote = quoteSum.divide(BigDecimal.valueOf(BASELINE), MC);
        BigDecimal meanSpread = spreadSum.divide(BigDecimal.valueOf(BASELINE), MC);
        for (int i = triggerIndex; i < tape.size(); i++) {
            MarketCandle c = tape.get(i);
            if (!valid(c) || (i > triggerIndex && c.closeTime()
                    != tape.get(i - 1).closeTime() + c.interval().millis()))
                return insufficient("VPA_SEQUENCE_INVALID_MARKET_DATA");
        }

        boolean longSide = signal.trigger().side() == BollingerMeanReversion.Side.LONG;
        BigDecimal triggerVolume = trigger.quoteVolume().divide(meanQuote, MC);
        BigDecimal triggerSpread = span(trigger).divide(meanSpread, MC);
        BigDecimal confirmedVolume = confirmation.quoteVolume().divide(meanQuote, MC);
        BigDecimal triggerWick = againstTrendWickRatio(trigger, longSide);
        boolean absorption = triggerVolume.compareTo(SPIKE) >= 0
                && (triggerSpread.compareTo(NARROW) <= 0
                    || triggerWick.compareTo(WICK) >= 0);
        boolean fade = false;
        for (int i = triggerIndex + 1; i < tape.size(); i++) {
            MarketCandle prior = tape.get(i - 1);
            MarketCandle current = tape.get(i);
            // Decreased effort AND narrower result compared with a prior bar.
            if (current.quoteVolume().compareTo(prior.quoteVolume()
                        .multiply(FADE_VOLUME)) <= 0
                    && span(current).compareTo(span(prior)
                        .multiply(FADE_SPREAD)) <= 0)
                fade = true;
        }
        // Failure to hold movement away from the SMA20; no band re-entry
        // or microstructure close-break requirement is reintroduced.
        boolean recovery = longSide
                ? confirmation.close().compareTo(trigger.close()) > 0
                : confirmation.close().compareTo(trigger.close()) < 0;
        boolean wick = againstTrendWickRatio(confirmation, longSide)
                .compareTo(WICK) >= 0;
        BigDecimal position = confirmation.close().subtract(confirmation.low())
                .divide(span(confirmation), MC);
        boolean rejection = wick && (longSide
                ? position.compareTo(FAVORABLE_CLOSE) >= 0
                : position.compareTo(BigDecimal.ONE.subtract(FAVORABLE_CLOSE)) <= 0);
        var continuationEffort = longSide
                ? VolumeSnapshot.EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME
                : VolumeSnapshot.EffortResult.BULLISH_WIDE_SPREAD_HIGH_VOLUME;
        boolean strong = lastObservation.effortResult() == continuationEffort;
        boolean qualify = (recovery || rejection) && (absorption || fade || rejection)
                && !strong;
        String reason = strong ? "VPA_SEQUENCE_STRONG_CONTINUATION"
                : !(recovery || rejection) ? "VPA_SEQUENCE_NO_CONTINUATION_FAILURE"
                : !(absorption || fade || rejection) ? "VPA_SEQUENCE_NO_EFFORT_RESULT_EVIDENCE"
                : null;
        return new Evidence(qualify, reason, tape.size() - triggerIndex,
                triggerVolume, triggerSpread, confirmedVolume,
                absorption, fade, recovery, rejection, strong);
    }

    private static Evidence insufficient(String reason) {
        return new Evidence(false, reason, 0, null, null, null,
                false, false, false, false, false);
    }

    private static boolean valid(MarketCandle c) {
        return c.closed() && c.numberOfTrades() > 0
                && c.quoteVolume().signum() > 0 && c.baseVolume().signum() > 0
                && span(c).signum() > 0;
    }

    private static BigDecimal span(MarketCandle c) {
        return c.high().subtract(c.low());
    }

    private static BigDecimal againstTrendWickRatio(MarketCandle c, boolean longSide) {
        BigDecimal wick = longSide
                ? c.open().min(c.close()).subtract(c.low())
                : c.high().subtract(c.open().max(c.close()));
        return span(c).signum() <= 0 ? BigDecimal.ZERO
                : wick.divide(span(c), MC);
    }
}
