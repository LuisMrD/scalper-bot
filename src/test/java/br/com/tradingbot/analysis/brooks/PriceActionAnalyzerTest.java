package br.com.tradingbot.analysis.brooks;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BreakoutState;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriceActionAnalyzerTest {
    private static final PriceActionAnalyzer ANALYZER = new PriceActionAnalyzer(
            new PriceActionParameters(20, 2, 2, 10, 20, .60, .25, .50));

    private static MarketCandle candle(int i, double open, double high, double low, double close) {
        return new MarketCandle("BTCUSDT", CandleInterval.M1,
                i * 60_000L, i * 60_000L + 59_999L,
                bd(open), bd(high), bd(low), bd(close),
                bd(100), bd(1000), 10, bd(60), bd(600), true);
    }
    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
    private static List<MarketCandle> flat(int length) {
        List<MarketCandle> out = new ArrayList<>();
        for (int i = 0; i < length; i++) out.add(candle(i, 100, 101, 99, 100));
        return out;
    }

    @Test void noTrendWithoutTwoConfirmedHighsAndLows() {
        var snapshot = ANALYZER.analyze(flat(20));
        assertEquals(Trend.UNDETERMINED, snapshot.trend());
        assertTrue(snapshot.confirmedPivots().isEmpty());
        assertTrue(snapshot.recentBars().stream().allMatch(p -> p.doji()));
    }

    @Test void detectsBreakoutOnlyAfterClosedBar() {
        var bars = flat(21);
        bars.set(20, candle(20, 100, 104, 99, 103));
        var result = ANALYZER.analyze(bars);
        assertEquals(BreakoutState.UP_CANDIDATE, result.breakout().state());
        assertEquals(0, bd(101).compareTo(result.breakout().level()));
        assertEquals(20 * 60_000L, result.breakout().breakoutBarOpenTime().longValue());
    }

    @Test void requiresAnotherBarForFollowThrough() {
        var bars = flat(22);
        bars.set(20, candle(20, 100, 104, 99, 103));
        bars.set(21, candle(21, 103, 107, 102, 106));
        var result = ANALYZER.analyze(bars);
        assertEquals(BreakoutState.UP_FOLLOW_THROUGH, result.breakout().state());
        assertEquals(20 * 60_000L, result.breakout().breakoutBarOpenTime().longValue());
    }

    @Test void detectsDownsideBreakout() {
        var bars = flat(21);
        bars.set(20, candle(20, 100, 101, 94, 95));
        assertEquals(BreakoutState.DOWN_CANDIDATE, ANALYZER.analyze(bars).breakout().state());
    }

    @Test void classifiesInsideAndOutsideBar() {
        var bars = flat(20);
        bars.set(18, candle(18, 100, 105, 95, 104));
        bars.set(19, candle(19, 102, 104, 96, 103));
        var result = ANALYZER.analyze(bars);
        assertTrue(result.recentBars().getLast().insideBar());
        bars.set(19, candle(19, 100, 106, 94, 105));
        assertTrue(ANALYZER.analyze(bars).recentBars().getLast().outsideBar());
    }

    @Test void pivotConfirmationDoesNotReadFuture() {
        var bars = flat(20);
        bars.set(18, candle(18, 100, 115, 99, 110));
        var result = ANALYZER.analyze(bars);
        assertTrue(result.confirmedPivots().stream().noneMatch(p -> p.pivotOpenTime() == 18 * 60_000L));
    }

    @Test void upTrendRequiresHigherHighsAndHigherLows() {
        double[] highs = {10,11,15,12,11,14,12,13,16,13,12,15,13,14,17,14,13,16,14,15,18,15,14,15};
        double[] lows =  { 8, 9,10, 9, 8, 9, 9,10,11,10, 9,10,10,11,12,11,10,11,11,12,13,12,11,12};
        List<MarketCandle> bars = new ArrayList<>();
        for(int i=0;i<highs.length;i++) {
            double mid=(highs[i]+lows[i])/2;
            bars.add(candle(i, mid, highs[i], lows[i], mid));
        }
        var result = ANALYZER.analyze(bars);
        assertEquals(Trend.UP, result.trend());
        assertTrue(result.confirmedPivots().stream().allMatch(p -> p.confirmedAtCloseTime() <= result.asOfCloseTime()));
    }

    @Test void rejectsMissingHistoryAndOpenOrGappedBars() {
        assertThrows(IllegalArgumentException.class, () -> ANALYZER.analyze(flat(19)));
        var bars = flat(20);
        bars.set(8, candle(9, 100, 101, 99, 100));
        var withGap = bars;
        assertThrows(IllegalArgumentException.class, () -> ANALYZER.analyze(withGap));
        bars = flat(20);
        var c = bars.get(19);
        bars.set(19, new MarketCandle(c.symbol(),c.interval(),c.openTime(),c.closeTime(),
                c.open(),c.high(),c.low(),c.close(),c.baseVolume(),c.quoteVolume(),
                c.numberOfTrades(),c.takerBuyBaseVolume(),c.takerBuyQuoteVolume(),false));
        var unfinished = bars;
        assertThrows(IllegalArgumentException.class, () -> ANALYZER.analyze(unfinished));
    }

    @Test void zeroRangeDoesNotDivideByZero() {
        var bars = flat(20);
        bars.set(19, candle(19, 100, 100, 100, 100));
        assertTrue(ANALYZER.analyze(bars).recentBars().getLast().doji());
    }
}
