package br.com.tradingbot.analysis.levels;

import br.com.tradingbot.analysis.brooks.PriceActionParameters;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.PivotOrigin;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.SwingDirection;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketLevelsAnalyzerTest {
    private static final MarketLevelsParameters LEVELS = new MarketLevelsParameters(14, 0.5, .10, 12);
    private static final PriceActionParameters PRICE_ACTION =
            new PriceActionParameters(20, 2, 2, 10, 20, .60, .25, .5);
    private final MarketLevelsAnalyzer analyzer = new MarketLevelsAnalyzer(LEVELS, PRICE_ACTION);

    @Test void confirmedSwingUpHasCorrectRetracementsAndExtensions() {
        var bars = baseline(30);
        bars.set(5, candle(5, 110, 80));
        bars.set(10, candle(10, 120, 100));
        bars.set(15, candle(15, 110, 90));
        bars.set(20, candle(20, 130, 100));
        var result = analyzer.analyze(bars);
        assertEquals(SwingDirection.UP, result.fibonacci().direction());
        assertEquals(0, result.fibonacci().startPrice().compareTo(bd("90")));
        assertEquals(0, result.fibonacci().endPrice().compareTo(bd("130")));
        assertLevel(result.fibonacci().retracements(), "0.500", "110");
        assertLevel(result.fibonacci().retracements(), "0.618", "105.28");
        assertLevel(result.fibonacci().extensions(), "1.618", "154.72");
        assertTrue(result.fibonacci().endConfirmedAtCloseTime() <= result.asOfCloseTime());
    }

    @Test void confirmedSwingDownHasSymmetricalMath() {
        var bars = baseline(30);
        bars.set(10, candle(10, 140, 100));
        bars.set(20, candle(20, 110, 80));
        var result = analyzer.analyze(bars);
        assertEquals(SwingDirection.DOWN, result.fibonacci().direction());
        assertLevel(result.fibonacci().retracements(), "0.500", "110");
        assertLevel(result.fibonacci().extensions(), "1.272", "63.68");
    }

    @Test void lastCandleSpikeIsNotAnUnconfirmedPivotOrFibonacciAnchor() {
        var bars = baseline(26);
        bars.set(5, candle(5, 110, 80));
        bars.set(10, candle(10, 120, 100));
        bars.set(15, candle(15, 110, 90));
        bars.set(20, candle(20, 130, 100));
        bars.set(25, candle(25, 999, 100)); // Sem as duas barras a direita.
        var result = analyzer.analyze(bars);
        assertEquals(0, result.fibonacci().endPrice().compareTo(bd("130")));
        assertTrue(result.zones().stream().noneMatch(z -> z.center().compareTo(bd("999")) == 0));
        assertTrue(result.zones().stream().allMatch(z -> z.lastConfirmedAtCloseTime() <= result.asOfCloseTime()));
    }

    @Test void nearbyLowsMakeOnePivotLowZone() {
        var bars = baseline(30);
        bars.set(5, candle(5, 110, 90));
        bars.set(15, candle(15, 110, 90.3));
        var result = analyzer.analyze(bars);
        assertTrue(result.zones().stream().anyMatch(z -> z.origin() == PivotOrigin.PIVOT_LOW
                && z.pivotCount() == 2
                && z.lowerBound().compareTo(bd("90")) < 0
                && z.upperBound().compareTo(bd("90.3")) > 0));
    }

    @Test void noOppositeConfirmedPivotsReturnsNullFibonacci() {
        var result = analyzer.analyze(baseline(30));
        assertNull(result.fibonacci());
        assertTrue(result.zones().isEmpty());
        assertEquals(30, result.candlesAnalyzed());
    }

    @Test void missingCandlesOrInsufficientHistoryAreRejected() {
        var bars = baseline(30);
        bars.remove(12);
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(bars));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(baseline(19)));
    }

    @Test void parametersRejectOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarketLevelsParameters(1, .5, .1, 12));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketLevelsParameters(14, Double.NaN, .1, 12));
        assertThrows(IllegalArgumentException.class,
                () -> new MarketLevelsParameters(14, .5, 0, 12));
    }

    @Test void zonesAreLimitedAndSortedByDistanceToLastPrice() {
        var bars = baseline(70);
        for (int i = 5; i < 65; i += 5) {
            bars.set(i, candle(i, 110, 95 - i * .15));
        }
        var a = new MarketLevelsAnalyzer(new MarketLevelsParameters(14, .05, .01, 2), PRICE_ACTION);
        var result = a.analyze(bars);
        assertTrue(result.zones().size() <= 2);
        for (int i = 1; i < result.zones().size(); i++) {
            var first = result.zones().get(i - 1).center().subtract(result.lastClose()).abs();
            var second = result.zones().get(i).center().subtract(result.lastClose()).abs();
            assertTrue(first.compareTo(second) <= 0);
        }
    }

    private static void assertLevel(List<MarketLevelsSnapshot.FibonacciLevel> levels, String ratio, String expected) {
        var found = levels.stream().filter(l -> l.ratio().compareTo(bd(ratio)) == 0).findFirst().orElseThrow();
        assertEquals(0, found.price().compareTo(bd(expected)));
    }
    private static ArrayList<MarketCandle> baseline(int size) {
        var result = new ArrayList<MarketCandle>();
        for (int i = 0; i < size; i++) result.add(candle(i, 110, 100));
        return result;
    }
    private static BigDecimal bd(String n) { return new BigDecimal(n); }
    private static MarketCandle candle(int i, double high, double low) {
        long time = 60_000L * i;
        return new MarketCandle("BTCUSDT", CandleInterval.M1, time, time + 59_999,
                bd("105"), BigDecimal.valueOf(high), BigDecimal.valueOf(low), bd("105"),
                bd("100"), bd("10000"), 10, bd("50"), bd("5000"), true);
    }
}
