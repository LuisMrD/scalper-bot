package br.com.tradingbot.simulation;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.risk.RiskPolicy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;

/**
 * Historical-only gate. No future candle is read. The inside-band close and
 * prior-bar microstructure break are checked by BollingerReversalConfirmation.
 */
final class BollingerFilteredPolicy {
    static final BigDecimal MINIMUM_NET_RR = new BigDecimal("1.5");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    enum StopMode { STRUCTURAL, EXTREME_CANDLE }
    record Verdict(boolean approved, BigDecimal stop, String rejection) {
        static Verdict reject(String why) { return new Verdict(false, null, why); }
    }

    Verdict evaluate(MultiTimeframeMarketContext market,
            BollingerReversalConfirmation.Confirmation confirmed,
            BacktestRequest request, RiskPolicy policy, StopMode mode) {
        MarketContextSnapshot primary = market.timeframes().getFirst();
        MarketCandle bar = primary.recentCandles().getLast();
        boolean longSide = confirmed.trigger().side() == BollingerMeanReversion.Side.LONG;
        var pa = primary.priceAction();
        var oppositeFollowThrough = longSide
                ? PriceActionSnapshot.BreakoutState.DOWN_FOLLOW_THROUGH
                : PriceActionSnapshot.BreakoutState.UP_FOLLOW_THROUGH;
        var oppositeTrend = longSide ? PriceActionSnapshot.Trend.DOWN
                : PriceActionSnapshot.Trend.UP;
        if (pa.trend() == oppositeTrend && pa.breakout().state() == oppositeFollowThrough)
            return Verdict.reject("STRONG_PRIMARY_TREND");
        for (int i = 1; i < market.timeframes().size(); i++) {
            var higher = market.timeframes().get(i).priceAction();
            if (higher.trend() == oppositeTrend
                    && higher.breakout().state() == oppositeFollowThrough)
                return Verdict.reject("STRONG_HIGHER_TIMEFRAME_TREND");
        }
        var observation = primary.volume().recentObservations().getLast();
        if (observation.closeTime() != bar.closeTime()
                || observation.volumeLevel() == VolumeSnapshot.VolumeLevel.LOW
                || observation.volumeLevel() == VolumeSnapshot.VolumeLevel.UNAVAILABLE)
            return Verdict.reject("LOW_OR_MISSING_VOLUME");
        var taker = longSide ? VolumeSnapshot.TakerBalance.BUY_DOMINANT
                : VolumeSnapshot.TakerBalance.SELL_DOMINANT;
        var rejection = longSide ? VolumeSnapshot.EffortResult.HIGH_VOLUME_LOWER_REJECTION
                : VolumeSnapshot.EffortResult.HIGH_VOLUME_UPPER_REJECTION;
        var continuation = longSide ? VolumeSnapshot.EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME
                : VolumeSnapshot.EffortResult.BULLISH_WIDE_SPREAD_HIGH_VOLUME;
        if (observation.effortResult() == continuation
                || (observation.takerBalance() != taker
                    && observation.effortResult() != rejection))
            return Verdict.reject("VOLUME_NO_REVERSAL_EVIDENCE");

        BigDecimal raw;
        if (mode == StopMode.EXTREME_CANDLE) {
            // Include all extremes seen between the outside close and confirmation,
            // then place stop ONE Binance tick beyond the extreme.
            raw = longSide ? confirmed.extremeLow().subtract(request.priceTick())
                    : confirmed.extremeHigh().add(request.priceTick());
        } else {
            var type = longSide ? PriceActionSnapshot.PivotType.LOW
                    : PriceActionSnapshot.PivotType.HIGH;
            var lastPivot = pa.confirmedPivots().stream()
                    .filter(p -> p.type() == type
                            && p.confirmedAtCloseTime() <= bar.closeTime()
                            && (longSide ? p.price().compareTo(bar.close()) < 0
                                         : p.price().compareTo(bar.close()) > 0))
                    .max(Comparator.comparingLong(
                            PriceActionSnapshot.ConfirmedPivot::confirmedAtCloseTime));
            if (lastPivot.isEmpty()) return Verdict.reject("NO_STRUCTURAL_PIVOT");
            BigDecimal atrBuffer = primary.marketLevels().atr()
                    .multiply(policy.structuralAtrBuffer(), MC);
            raw = longSide ? lastPivot.get().price().subtract(atrBuffer)
                    : lastPivot.get().price().add(atrBuffer);
        }
        if (raw.signum() <= 0) return Verdict.reject("INVALID_STOP");
        BigDecimal stop = raw.divide(request.priceTick(), 0,
                longSide ? RoundingMode.FLOOR : RoundingMode.CEILING)
                .multiply(request.priceTick());
        BigDecimal entry = bar.close();
        if (longSide ? stop.compareTo(entry) >= 0 : stop.compareTo(entry) <= 0)
            return Verdict.reject("INVALID_STOP_SIDE");
        BigDecimal target = confirmed.trigger().middle();
        // Net of fees + slippage on BOTH sides, before any entry is queued.
        BigDecimal costRate = policy.feePerSidePct().add(policy.slippagePerSidePct())
                .divide(HUNDRED, MC);
        BigDecimal netReward = (longSide ? target.subtract(entry) : entry.subtract(target))
                .subtract(entry.add(target).multiply(costRate, MC));
        BigDecimal risk = entry.subtract(stop).abs()
                .add(entry.add(stop).multiply(costRate, MC));
        if (netReward.signum() <= 0
                || netReward.compareTo(risk.multiply(MINIMUM_NET_RR, MC)) < 0)
            return Verdict.reject("NET_REWARD_RISK_BELOW_1_5");
        if (policy.costToInitialRiskTooHigh(entry, stop))
            return Verdict.reject("COST_TO_RISK");
        // Structural case delegates real stop calculation to RiskEngine; the
        // value computed here is exclusively used for the identical R:R gate.
        return new Verdict(true, mode == StopMode.EXTREME_CANDLE ? stop : null, null);
    }
}
