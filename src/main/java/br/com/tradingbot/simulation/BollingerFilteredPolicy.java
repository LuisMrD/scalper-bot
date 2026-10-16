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
 * Historical-only gate. No future candle is read. The original Bollinger
 * reversal patterns remain unchanged; only Volume/Price Action, trend and
 * cost-adjusted reward/risk checks are added to filtered research arms.
 */
final class BollingerFilteredPolicy {
    static final BigDecimal MINIMUM_NET_RR = new BigDecimal("1.5");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    enum StopMode { STRUCTURAL, EXTREME_CANDLE }
    enum VolumeMode { DIRECTIONAL_LEGACY, SEQUENCE_EFFORT_RESULT }
    record Verdict(boolean approved, BigDecimal stop, String rejection) {
        static Verdict reject(String why) { return new Verdict(false, null, why); }
    }

    /**
     * Full, read-only assessment at signal CLOSE. Unlike the execution verdict,
     * this evaluates BOTH independent stop/risk branches even when an earlier
     * filter blocks the signal. Only completed candles are read.
     */
    record Diagnostic(StopMode stopMode, boolean approved, String firstRejection,
            java.util.List<String> failedChecks, String primaryTrend,
            String primaryBreakout, boolean strongHigherTimeframeTrend,
            String volumeLevel, String takerBalance, String effortResult,
            long volumeCloseTime, BigDecimal signalClosePrice,
            BigDecimal frozenMiddleTarget, BigDecimal stopPrice,
            BigDecimal targetDistancePrice, BigDecimal stopDistancePrice,
            BigDecimal netRewardPrice, BigDecimal totalRiskPrice,
            BigDecimal netRewardRisk, BigDecimal costToRiskPct,
            BigDecimal extremeLow, BigDecimal extremeHigh,
            VolumeMode volumeMode, BollingerSequenceVpa.Evidence sequenceEvidence) {
        // Existing diagnostic creation/serialization tests retain compatibility.
        Diagnostic(StopMode stopMode, boolean approved, String firstRejection,
                java.util.List<String> failedChecks, String primaryTrend,
                String primaryBreakout, boolean strongHigherTimeframeTrend,
                String volumeLevel, String takerBalance, String effortResult,
                long volumeCloseTime, BigDecimal signalClosePrice,
                BigDecimal frozenMiddleTarget, BigDecimal stopPrice,
                BigDecimal targetDistancePrice, BigDecimal stopDistancePrice,
                BigDecimal netRewardPrice, BigDecimal totalRiskPrice,
                BigDecimal netRewardRisk, BigDecimal costToRiskPct,
                BigDecimal extremeLow, BigDecimal extremeHigh) {
            this(stopMode, approved, firstRejection, failedChecks, primaryTrend,
                    primaryBreakout, strongHigherTimeframeTrend, volumeLevel,
                    takerBalance, effortResult, volumeCloseTime, signalClosePrice,
                    frozenMiddleTarget, stopPrice, targetDistancePrice,
                    stopDistancePrice, netRewardPrice, totalRiskPrice,
                    netRewardRisk, costToRiskPct, extremeLow, extremeHigh,
                    VolumeMode.DIRECTIONAL_LEGACY, null);
        }
    }

    Verdict evaluate(MultiTimeframeMarketContext market,
            BollingerReversalConfirmation.Confirmation confirmed,
            BacktestRequest request, RiskPolicy policy, StopMode mode) {
        Diagnostic result = diagnose(market, confirmed, request, policy, mode);
        return new Verdict(result.approved(),
                result.approved() && mode == StopMode.EXTREME_CANDLE
                        ? result.stopPrice() : null,
                result.firstRejection());
    }

    Diagnostic diagnose(MultiTimeframeMarketContext market,
            BollingerReversalConfirmation.Confirmation confirmed,
            BacktestRequest request, RiskPolicy policy, StopMode mode) {
        return diagnose(market, confirmed, request, policy, mode,
                VolumeMode.DIRECTIONAL_LEGACY);
    }

    Diagnostic diagnose(MultiTimeframeMarketContext market,
            BollingerReversalConfirmation.Confirmation confirmed,
            BacktestRequest request, RiskPolicy policy, StopMode mode,
            VolumeMode volumeMode) {
        MarketContextSnapshot primary = market.timeframes().getFirst();
        MarketCandle bar = primary.recentCandles().getLast();
        boolean longSide = confirmed.trigger().side() == BollingerMeanReversion.Side.LONG;
        var pa = primary.priceAction();
        var oppositeFollowThrough = longSide
                ? PriceActionSnapshot.BreakoutState.DOWN_FOLLOW_THROUGH
                : PriceActionSnapshot.BreakoutState.UP_FOLLOW_THROUGH;
        var oppositeTrend = longSide ? PriceActionSnapshot.Trend.DOWN
                : PriceActionSnapshot.Trend.UP;
        var failed = new java.util.ArrayList<String>();

        if (pa.trend() == oppositeTrend && pa.breakout().state() == oppositeFollowThrough)
            failed.add("STRONG_PRIMARY_TREND");
        boolean strongHigherTrend = false;
        for (int i = 1; i < market.timeframes().size(); i++) {
            var higher = market.timeframes().get(i).priceAction();
            if (higher.trend() == oppositeTrend
                    && higher.breakout().state() == oppositeFollowThrough)
                strongHigherTrend = true;
        }
        if (strongHigherTrend) failed.add("STRONG_HIGHER_TIMEFRAME_TREND");

        var observation = primary.volume().recentObservations().getLast();
        if (observation.closeTime() != bar.closeTime()
                || observation.volumeLevel() == VolumeSnapshot.VolumeLevel.LOW
                || observation.volumeLevel() == VolumeSnapshot.VolumeLevel.UNAVAILABLE)
            failed.add("LOW_OR_MISSING_VOLUME");
        var taker = longSide ? VolumeSnapshot.TakerBalance.BUY_DOMINANT
                : VolumeSnapshot.TakerBalance.SELL_DOMINANT;
        var rejection = longSide ? VolumeSnapshot.EffortResult.HIGH_VOLUME_LOWER_REJECTION
                : VolumeSnapshot.EffortResult.HIGH_VOLUME_UPPER_REJECTION;
        var continuation = longSide ? VolumeSnapshot.EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME
                : VolumeSnapshot.EffortResult.BULLISH_WIDE_SPREAD_HIGH_VOLUME;
        BollingerSequenceVpa.Evidence sequenceEvidence = null;
        if (volumeMode == VolumeMode.DIRECTIONAL_LEGACY) {
            if (observation.effortResult() == continuation
                    || (observation.takerBalance() != taker
                        && observation.effortResult() != rejection))
                failed.add("VOLUME_NO_REVERSAL_EVIDENCE");
        } else {
            // The sequence observes only closed candles available at this signal.
            // LOW / UNAVAILABLE volume and strong-trend vetoes still apply.
            sequenceEvidence = new BollingerSequenceVpa().assess(
                    primary.recentCandles(), confirmed, observation);
            if (!sequenceEvidence.approved())
                failed.add(sequenceEvidence.rejection());
        }

        BigDecimal entry = bar.close();
        BigDecimal target = confirmed.trigger().middle();
        BigDecimal targetDistance = longSide
                ? target.subtract(entry) : entry.subtract(target);
        BigDecimal stop = null;
        BigDecimal stopDistance = null;
        BigDecimal risk = null;
        BigDecimal netReward = null;
        BigDecimal netRR = null;
        BigDecimal costToRiskPct = null;

        BigDecimal raw = null;
        if (mode == StopMode.EXTREME_CANDLE) {
            raw = longSide ? confirmed.extremeLow().subtract(request.priceTick())
                    : confirmed.extremeHigh().add(request.priceTick());
        } else {
            var type = longSide ? PriceActionSnapshot.PivotType.LOW
                    : PriceActionSnapshot.PivotType.HIGH;
            var lastPivot = pa.confirmedPivots().stream()
                    .filter(p -> p.type() == type
                            && p.confirmedAtCloseTime() <= bar.closeTime()
                            && (longSide ? p.price().compareTo(entry) < 0
                                         : p.price().compareTo(entry) > 0))
                    .max(Comparator.comparingLong(
                            PriceActionSnapshot.ConfirmedPivot::confirmedAtCloseTime));
            if (lastPivot.isEmpty()) failed.add("NO_STRUCTURAL_PIVOT");
            else {
                BigDecimal atrBuffer = primary.marketLevels().atr()
                        .multiply(policy.structuralAtrBuffer(), MC);
                raw = longSide ? lastPivot.get().price().subtract(atrBuffer)
                        : lastPivot.get().price().add(atrBuffer);
            }
        }
        if (raw != null) {
            if (raw.signum() <= 0) {
                failed.add("INVALID_STOP");
            } else {
                stop = raw.divide(request.priceTick(), 0,
                        longSide ? RoundingMode.FLOOR : RoundingMode.CEILING)
                        .multiply(request.priceTick());
                if (longSide ? stop.compareTo(entry) >= 0
                        : stop.compareTo(entry) <= 0) {
                    failed.add("INVALID_STOP_SIDE");
                } else {
                    stopDistance = entry.subtract(stop).abs();
                    BigDecimal costRate = policy.feePerSidePct()
                            .add(policy.slippagePerSidePct()).divide(HUNDRED, MC);
                    netReward = targetDistance.subtract(
                            entry.add(target).multiply(costRate, MC));
                    risk = stopDistance.add(entry.add(stop).multiply(costRate, MC));
                    netRR = netReward.divide(risk, MC);
                    if (netReward.signum() <= 0
                            || netReward.compareTo(risk.multiply(MINIMUM_NET_RR, MC)) < 0)
                        failed.add("NET_REWARD_RISK_BELOW_1_5");
                    costToRiskPct = entry.add(stop).multiply(costRate, MC)
                            .divide(stopDistance, MC).multiply(HUNDRED, MC);
                    if (policy.costToInitialRiskTooHigh(entry, stop))
                        failed.add("COST_TO_RISK");
                }
            }
        }
        return new Diagnostic(mode, failed.isEmpty(),
                failed.isEmpty() ? null : failed.getFirst(), java.util.List.copyOf(failed),
                pa.trend().name(), pa.breakout().state().name(), strongHigherTrend,
                observation.volumeLevel().name(), observation.takerBalance().name(),
                observation.effortResult().name(), observation.closeTime(),
                entry, target, stop, targetDistance, stopDistance,
                netReward, risk, netRR, costToRiskPct,
                confirmed.extremeLow(), confirmed.extremeHigh(),
                volumeMode, sequenceEvidence);
    }
}
