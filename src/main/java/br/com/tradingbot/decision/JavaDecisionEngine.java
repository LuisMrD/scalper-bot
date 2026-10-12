package br.com.tradingbot.decision;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.decision.DecisionResult.Entry;
import br.com.tradingbot.decision.DecisionResult.PositionAction;
import br.com.tradingbot.decision.DecisionResult.Reason;
import br.com.tradingbot.decision.DecisionResult.Setup;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Motor puramente deterministico e somente leitura.
 * Usa o primeiro timeframe como timeframe de entrada; os demais sao contexto.
 * Os setups sao HIPOTESES para simulacao, nao garantias estatisticas.
 */
public final class JavaDecisionEngine implements DecisionEngine {
    private final JavaDecisionParameters settings;

    public JavaDecisionEngine(JavaDecisionParameters settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    @Override
    public DecisionResult evaluate(MultiTimeframeMarketContext context, PositionState position) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(position, "position");
        var primary = context.timeframes().getFirst();
        var pa = primary.priceAction();
        var volume = primary.volume().recentObservations();
        var candle = primary.recentCandles().getLast();
        Trend trend = pa.trend();
        long maxAge = primary.recentCandles().getLast().interval().millis()
                * (long) settings.maxPrimaryStalenessBars();
        if (context.requestedAsOfEpochMs() - primary.asOfCloseTime() > maxAge) {
            return result(context, primary, Entry.WAIT,
                    position.side() == PositionState.Side.FLAT ? PositionAction.NONE : PositionAction.HOLD,
                    Setup.NONE, Reason.STALE_MARKET_DATA);
        }

        // Uma posicao ja aberta NUNCA pode receber nova entrada desta avaliacao.
        if (position.side() != PositionState.Side.FLAT) {
            boolean oppositeTrend = position.side() == PositionState.Side.LONG
                    ? trend == Trend.DOWN : trend == Trend.UP;
            boolean oppositeBar = position.side() == PositionState.Side.LONG
                    ? candle.close().compareTo(candle.open()) < 0
                    : candle.close().compareTo(candle.open()) > 0;
            var currentVolume = volume.getLast();
            boolean oppositeVolume = position.side() == PositionState.Side.LONG
                    ? currentVolume.volumeLevel() == VolumeSnapshot.VolumeLevel.HIGH
                        && currentVolume.takerBalance() == VolumeSnapshot.TakerBalance.SELL_DOMINANT
                    : currentVolume.volumeLevel() == VolumeSnapshot.VolumeLevel.HIGH
                        && currentVolume.takerBalance() == VolumeSnapshot.TakerBalance.BUY_DOMINANT;
            boolean exit = oppositeTrend && oppositeBar && oppositeVolume;
            return result(context, primary, Entry.WAIT,
                    exit ? PositionAction.EXIT : PositionAction.HOLD,
                    Setup.NONE, exit ? Reason.CONFIRMED_REVERSAL : Reason.POSITION_HOLD);
        }

        if (trend != Trend.UP && trend != Trend.DOWN)
            return waitFor(context, primary, Reason.TREND_UNCLEAR);
        if (settings.requireHigherTimeframeAlignment()) {
            for (int i = 1; i < context.timeframes().size(); i++) {
                if (context.timeframes().get(i).priceAction().trend() != trend)
                    return waitFor(context, primary, Reason.HIGHER_TIMEFRAME_CONFLICT);
            }
        }

        boolean bullish = trend == Trend.UP;
        Setup setup = Setup.NONE;
        if (settings.pullbackEnabled() && isPullback(primary, bullish)) setup = Setup.PULLBACK;
        else if (settings.breakoutRetestEnabled() && isBreakoutRetest(primary, bullish))
            setup = Setup.BREAKOUT_RETEST;
        if (setup == Setup.NONE) return waitFor(context, primary, Reason.NO_SETUP);
        if (!isVolumeConfirmed(volume.getLast(), candle, bullish))
            return waitFor(context, primary, Reason.VOLUME_NOT_CONFIRMED);
        return result(context, primary, bullish ? Entry.LONG : Entry.SHORT,
                PositionAction.NONE, setup,
                setup == Setup.PULLBACK ? Reason.PULLBACK_CONFIRMED : Reason.BREAKOUT_RETEST_CONFIRMED);
    }

    private boolean isVolumeConfirmed(VolumeSnapshot.Observation observation,
            MarketCandle candle, boolean bullish) {
        if (observation.volumeLevel() == VolumeSnapshot.VolumeLevel.LOW
                || observation.volumeLevel() == VolumeSnapshot.VolumeLevel.UNAVAILABLE)
            return false;
        if (settings.requireDominantTakerVolume()) {
            if (observation.takerBalance() != (bullish
                    ? VolumeSnapshot.TakerBalance.BUY_DOMINANT
                    : VolumeSnapshot.TakerBalance.SELL_DOMINANT)) return false;
        }
        // Volume de direcao oposta e uma contradicao forte mesmo sem o filtro dominante.
        if (bullish && observation.effortResult() == VolumeSnapshot.EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME)
            return false;
        if (!bullish && observation.effortResult() == VolumeSnapshot.EffortResult.BULLISH_WIDE_SPREAD_HIGH_VOLUME)
            return false;
        return bullish ? candle.close().compareTo(candle.open()) > 0
                : candle.close().compareTo(candle.open()) < 0;
    }

    private boolean isPullback(MarketContextSnapshot ctx, boolean bullish) {
        List<MarketCandle> candles = ctx.recentCandles();
        if (candles.size() < 2) return false;
        var prev = candles.get(candles.size() - 2);
        var last = candles.getLast();
        var recentPatterns = ctx.priceAction().recentBars();
        if (recentPatterns.isEmpty() || recentPatterns.getLast().openTime() != last.openTime())
            return false;
        var pattern = recentPatterns.getLast();
        if (bullish) {
            if (prev.close().compareTo(prev.open()) >= 0 || last.close().compareTo(prev.high()) <= 0
                    || !(pattern.trendBar() || pattern.bullishRejection())) return false;
            return touchesSupportOrFib(ctx.marketLevels(), prev.low(), true);
        }
        if (prev.close().compareTo(prev.open()) <= 0 || last.close().compareTo(prev.low()) >= 0
                || !(pattern.trendBar() || pattern.bearishRejection())) return false;
        return touchesSupportOrFib(ctx.marketLevels(), prev.high(), false);
    }

    private boolean touchesSupportOrFib(MarketLevelsSnapshot levels, BigDecimal price, boolean bullish) {
        BigDecimal tolerance = levels.zoneTolerance();
        for (var zone : levels.zones()) {
            if (zone.origin() != (bullish ? MarketLevelsSnapshot.PivotOrigin.PIVOT_LOW
                    : MarketLevelsSnapshot.PivotOrigin.PIVOT_HIGH)) continue;
            if (price.compareTo(zone.lowerBound().subtract(tolerance)) >= 0
                    && price.compareTo(zone.upperBound().add(tolerance)) <= 0) return true;
        }
        var fib = levels.fibonacci();
        if (fib != null && fib.direction() == (bullish
                ? MarketLevelsSnapshot.SwingDirection.UP : MarketLevelsSnapshot.SwingDirection.DOWN)) {
            for (var retracement : fib.retracements()) {
                if (price.subtract(retracement.price()).abs().compareTo(tolerance) <= 0) return true;
            }
        }
        return false;
    }

    /**
     * Um candle ANTERIOR fecha alem do extremo das N barras anteriores;
     * o candle ATUAL testa essa linha por dentro da tolerancia e fecha do lado do rompimento.
     * Nao confunde uma barra que acabou de romper com um reteste confirmado.
     */
    private boolean isBreakoutRetest(MarketContextSnapshot ctx, boolean bullish) {
        List<MarketCandle> candles = ctx.recentCandles();
        int lastIndex = candles.size() - 1;
        BigDecimal tolerance = ctx.marketLevels().zoneTolerance();
        for (int breakout = lastIndex - 1;
                breakout >= Math.max(settings.breakoutLookback(), lastIndex - settings.retestMaxBars());
                breakout--) {
            BigDecimal boundary = bullish ? candles.get(breakout - settings.breakoutLookback()).high()
                    : candles.get(breakout - settings.breakoutLookback()).low();
            for (int i = breakout - settings.breakoutLookback() + 1; i < breakout; i++) {
                boundary = bullish ? boundary.max(candles.get(i).high()) : boundary.min(candles.get(i).low());
            }
            var breaking = candles.get(breakout);
            boolean validBreakout = bullish ? breaking.close().compareTo(boundary) > 0
                    && breaking.close().compareTo(breaking.open()) > 0
                    : breaking.close().compareTo(boundary) < 0
                    && breaking.close().compareTo(breaking.open()) < 0;
            if (!validBreakout) continue;
            boolean invalidated = false;
            for (int i = breakout + 1; i < lastIndex; i++) {
                var close = candles.get(i).close();
                if (bullish ? close.compareTo(boundary.subtract(tolerance)) < 0
                        : close.compareTo(boundary.add(tolerance)) > 0) {
                    invalidated = true;
                    break;
                }
            }
            if (invalidated) continue;
            var last = candles.getLast();
            BigDecimal test = bullish ? last.low() : last.high();
            boolean touched = test.subtract(boundary).abs().compareTo(tolerance) <= 0;
            boolean recovered = bullish ? last.close().compareTo(boundary) > 0
                    && last.close().compareTo(last.open()) > 0
                    : last.close().compareTo(boundary) < 0
                    && last.close().compareTo(last.open()) < 0;
            if (touched && recovered) return true;
        }
        return false;
    }

    private static DecisionResult waitFor(MultiTimeframeMarketContext context,
            MarketContextSnapshot primary, Reason reason) {
        return result(context, primary, Entry.WAIT, PositionAction.NONE, Setup.NONE, reason);
    }

    private static DecisionResult result(MultiTimeframeMarketContext context,
            MarketContextSnapshot primary, Entry entry, PositionAction management, Setup setup, Reason reason) {
        return new DecisionResult("JAVA", context.symbol(), primary.interval(),
                context.requestedAsOfEpochMs(), primary.asOfCloseTime(), entry, management, setup, reason);
    }
}
