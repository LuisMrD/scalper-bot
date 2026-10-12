package br.com.tradingbot.decision;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.context.MarketContextSnapshot;
import br.com.tradingbot.analysis.context.MultiTimeframeMarketContext;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaDecisionEngineTest {
    private static final long CUTOFF = 300_000_000L;
    private static final JavaDecisionParameters OPTIONS =
            new JavaDecisionParameters(3, 4, 2, true, true, true, true);
    private final JavaDecisionEngine engine = new JavaDecisionEngine(OPTIONS);

    @Test void returnsLongOnConfirmedBullishPullbackNearSupport() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 13, 103, 104, 100, 101);
        replace(candles, 14, 101, 106, 101, 105);
        var ctx = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.BUY_DOMINANT, true,
                List.of(support()));
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.LONG, result.entry());
        assertEquals(DecisionResult.Setup.PULLBACK, result.setup());
        assertEquals(DecisionResult.Reason.PULLBACK_CONFIRMED, result.reason());
        assertEquals(DecisionResult.PositionAction.NONE, result.positionAction());
    }

    @Test void returnsShortOnConfirmedBearishPullbackNearResistance() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 13, 100, 103, 99, 102);
        replace(candles, 14, 102, 103, 97, 98);
        var ctx = context(candles, PriceActionSnapshot.Trend.DOWN,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.SELL_DOMINANT, true,
                List.of(resistance()));
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.SHORT, result.entry());
        assertEquals(DecisionResult.Setup.PULLBACK, result.setup());
    }

    @Test void longBreakoutRequiresPriorBreakAndSubsequentRetest() {
        var candles = base(CandleInterval.M5, 15, 99, 99, 100, 90);
        replace(candles, 11, 99, 105, 95, 104); // Ja rompeu no passado.
        replace(candles, 12, 103, 106, 102, 104);
        replace(candles, 13, 104, 106, 101, 103);
        replace(candles, 14, 102, 106, 100, 105); // Retestou e fechou acima.
        var ctx = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.NORMAL, VolumeSnapshot.TakerBalance.BUY_DOMINANT, true,
                List.of());
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.LONG, result.entry());
        assertEquals(DecisionResult.Setup.BREAKOUT_RETEST, result.setup());
    }

    @Test void shortBreakoutRequiresPriorBreakAndSubsequentRetest() {
        var candles = base(CandleInterval.M5, 15, 101, 101, 110, 100);
        replace(candles, 11, 101, 105, 95, 96);
        replace(candles, 12, 98, 100, 95, 98);
        replace(candles, 13, 99, 100, 95, 99);
        replace(candles, 14, 99, 100, 94, 96);
        var ctx = context(candles, PriceActionSnapshot.Trend.DOWN,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.SELL_DOMINANT, true,
                List.of());
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.SHORT, result.entry());
        assertEquals(DecisionResult.Setup.BREAKOUT_RETEST, result.setup());
    }

    @Test void newBreakoutWithoutRetestIsNotAnEntry() {
        var candles = base(CandleInterval.M5, 15, 99, 99, 100, 90);
        replace(candles, 14, 99, 105, 95, 104); // Rompimento apenas na ultima barra.
        var ctx = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.BUY_DOMINANT, true,
                List.of());
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Reason.NO_SETUP, result.reason());
    }

    @Test void higherTimeframeDisagreementBlocksEntry() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 13, 103, 104, 100, 101);
        replace(candles, 14, 101, 106, 101, 105);
        var primary = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.BUY_DOMINANT, true,
                List.of(support()));
        var secondary = context(base(CandleInterval.M15, 15, 100, 101, 105, 99),
                PriceActionSnapshot.Trend.DOWN, VolumeSnapshot.VolumeLevel.NORMAL,
                VolumeSnapshot.TakerBalance.BALANCED, true, List.of());
        var result = engine.evaluate(multi(primary, secondary), PositionState.flat());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Reason.HIGHER_TIMEFRAME_CONFLICT, result.reason());
    }

    @Test void weakVolumeBlocksOtherwiseValidSetup() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 13, 103, 104, 100, 101);
        replace(candles, 14, 101, 106, 101, 105);
        var ctx = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.LOW, VolumeSnapshot.TakerBalance.BUY_DOMINANT,
                true, List.of(support()));
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Reason.VOLUME_NOT_CONFIRMED, result.reason());
    }

    @Test void unclearTrendMeansWaitEvenWithSupport() {
        var ctx = context(base(CandleInterval.M5, 15, 100, 101, 105, 99),
                PriceActionSnapshot.Trend.RANGE, VolumeSnapshot.VolumeLevel.HIGH,
                VolumeSnapshot.TakerBalance.BUY_DOMINANT, true, List.of(support()));
        var result = engine.evaluate(multi(ctx), PositionState.flat());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Reason.TREND_UNCLEAR, result.reason());
    }

    @Test void existingLongOnlyExitsOnConfirmedOppositeTrendAndVolume() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 14, 103, 104, 98, 99);
        var ctx = context(candles, PriceActionSnapshot.Trend.DOWN,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.SELL_DOMINANT, true,
                List.of());
        var result = engine.evaluate(multi(ctx), new PositionState(PositionState.Side.LONG));
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.PositionAction.EXIT, result.positionAction());
        assertEquals(DecisionResult.Reason.CONFIRMED_REVERSAL, result.reason());
    }

    @Test void existingShortOnlyExitsOnConfirmedOppositeTrendAndVolume() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 14, 100, 106, 99, 105);
        var ctx = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.BUY_DOMINANT, true,
                List.of());
        var result = engine.evaluate(multi(ctx), new PositionState(PositionState.Side.SHORT));
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.PositionAction.EXIT, result.positionAction());
    }

    @Test void existingLongHoldsOnOppositeTrendWithoutStrongVolume() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 14, 103, 104, 98, 99);
        var ctx = context(candles, PriceActionSnapshot.Trend.DOWN,
                VolumeSnapshot.VolumeLevel.NORMAL, VolumeSnapshot.TakerBalance.SELL_DOMINANT,
                true, List.of());
        var result = engine.evaluate(multi(ctx), new PositionState(PositionState.Side.LONG));
        assertEquals(DecisionResult.PositionAction.HOLD, result.positionAction());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
    }

    @Test void stalePrimaryCandlesNeverOpenNewEntry() {
        var candles = base(CandleInterval.M5, 15, 100, 101, 105, 99);
        replace(candles, 13, 103, 104, 100, 101);
        replace(candles, 14, 101, 106, 101, 105);
        var previous = context(candles, PriceActionSnapshot.Trend.UP,
                VolumeSnapshot.VolumeLevel.HIGH, VolumeSnapshot.TakerBalance.BUY_DOMINANT,
                true, List.of(support()));
        long late = CUTOFF + CandleInterval.M5.millis() * 3;
        var stale = new MarketContextSnapshot("BTCUSDT", "5m", late,
                previous.asOfCloseTime(), previous.candlesAnalyzed(), previous.recentCandles(),
                previous.priceAction(), previous.volume(), previous.marketLevels());
        var result = engine.evaluate(new MultiTimeframeMarketContext("BTCUSDT", late, List.of(stale)),
                PositionState.flat());
        assertEquals(DecisionResult.Entry.WAIT, result.entry());
        assertEquals(DecisionResult.Reason.STALE_MARKET_DATA, result.reason());
    }

    @Test void rejectsInvalidParametersAndContradictoryResults() {
        assertThrows(IllegalArgumentException.class,
                () -> new JavaDecisionParameters(2, 4, 2, true, true, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new JavaDecisionParameters(10, 4, 2, true, true, false, false));
        assertThrows(IllegalArgumentException.class, () -> new DecisionResult(
                "JAVA", "BTCUSDT", "5m", CUTOFF, 1, DecisionResult.Entry.LONG,
                DecisionResult.PositionAction.HOLD, DecisionResult.Setup.PULLBACK,
                DecisionResult.Reason.PULLBACK_CONFIRMED));
    }

    static MarketLevelsSnapshot.PriceZone support() {
        return new MarketLevelsSnapshot.PriceZone(MarketLevelsSnapshot.PivotOrigin.PIVOT_LOW,
                MarketLevelsSnapshot.ZonePosition.BELOW, bd(100), bd(99), bd(101), 2, 1, 2);
    }
    static MarketLevelsSnapshot.PriceZone resistance() {
        return new MarketLevelsSnapshot.PriceZone(MarketLevelsSnapshot.PivotOrigin.PIVOT_HIGH,
                MarketLevelsSnapshot.ZonePosition.ABOVE, bd(103), bd(102), bd(104), 2, 1, 2);
    }
    static BigDecimal bd(long v) { return BigDecimal.valueOf(v); }

    static ArrayList<MarketCandle> base(CandleInterval tf, int count, long open, long close, long high, long low) {
        var out = new ArrayList<MarketCandle>();
        for (int i=0; i<count; i++) {
            long time = CUTOFF - (long) (count - i) * tf.millis();
            out.add(new MarketCandle("BTCUSDT", tf, time, time + tf.millis() - 1,
                    bd(open), bd(high), bd(low), bd(close), bd(50), bd(5000),
                    15, bd(30), bd(3000), true));
        }
        return out;
    }
    static void replace(List<MarketCandle> candles, int i, long open, long high, long low, long close) {
        MarketCandle old = candles.get(i);
        candles.set(i, new MarketCandle(old.symbol(), old.interval(), old.openTime(), old.closeTime(),
                bd(open), bd(high), bd(low), bd(close), old.baseVolume(), old.quoteVolume(),
                old.numberOfTrades(), old.takerBuyBaseVolume(), old.takerBuyQuoteVolume(), true));
    }

    static MarketContextSnapshot context(List<MarketCandle> candles, PriceActionSnapshot.Trend trend,
            VolumeSnapshot.VolumeLevel volumeLevel, VolumeSnapshot.TakerBalance taker,
            boolean trendBar, List<MarketLevelsSnapshot.PriceZone> zones) {
        MarketCandle last = candles.getLast();
        var pattern = new PriceActionSnapshot.BarPattern(last.openTime(),
                last.close().compareTo(last.open()) >= 0 ? PriceActionSnapshot.Direction.BULL :
                        PriceActionSnapshot.Direction.BEAR,
                trendBar, false, false, false, true, true);
        var price = new PriceActionSnapshot(last.symbol(), last.interval().code(), last.closeTime(),
                candles.size(), trend, List.of(pattern), List.of(),
                PriceActionSnapshot.Breakout.none(), false);
        var observation = new VolumeSnapshot.Observation(last.openTime(), last.closeTime(),
                last.baseVolume(), last.quoteVolume(), bd(2), bd(2), bd(1), volumeLevel,
                VolumeSnapshot.SpreadLevel.NORMAL, taker, VolumeSnapshot.EffortResult.NONE);
        var volume = new VolumeSnapshot(last.symbol(), last.interval().code(), last.closeTime(),
                candles.size(), 10, List.of(observation));
        var levels = new MarketLevelsSnapshot(last.symbol(), last.interval().code(), last.closeTime(),
                candles.size(), last.close(), bd(2), bd(2), zones, null);
        return new MarketContextSnapshot(last.symbol(), last.interval().code(), CUTOFF,
                last.closeTime(), candles.size(), candles, price, volume, levels);
    }
    static MultiTimeframeMarketContext multi(MarketContextSnapshot... contexts) {
        return new MultiTimeframeMarketContext("BTCUSDT", CUTOFF, List.of(contexts));
    }
}
