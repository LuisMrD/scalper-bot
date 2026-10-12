package br.com.tradingbot.analysis.context;

import br.com.tradingbot.analysis.brooks.PriceActionParameters;
import br.com.tradingbot.analysis.levels.MarketLevelsParameters;
import br.com.tradingbot.analysis.volume.VolumeParameters;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketContextBuilderTest {
    private static final MarketContextBuilder BUILDER = new MarketContextBuilder(
            new PriceActionParameters(20, 2, 2, 10, 20, .6, .25, .5),
            new VolumeParameters(20, 10, 1.8, .6, 1.5, .7, .6, .55, .75),
            new MarketLevelsParameters(14, .5, .1, 12), 3);

    @Test void allAnalysesReceiveOneConsistentWindow() {
        var data = candles(30);
        long cutoff = data.getLast().closeTime();
        var result = BUILDER.build(data, cutoff);
        assertEquals("BTCUSDT", result.symbol());
        assertEquals("1m", result.interval());
        assertEquals(30, result.candlesAnalyzed());
        assertEquals(3, result.recentCandles().size());
        assertEquals(data.getLast().closeTime(), result.asOfCloseTime());
        assertEquals(result.asOfCloseTime(), result.priceAction().asOfCloseTime());
        assertEquals(result.asOfCloseTime(), result.volume().asOfCloseTime());
        assertEquals(result.asOfCloseTime(), result.marketLevels().asOfCloseTime());
        assertEquals(10, result.volume().recentObservations().size());
        assertEquals(20, result.volume().referenceLookback());
        assertEquals(data.getLast(), result.recentCandles().getLast());
        assertThrows(UnsupportedOperationException.class, () -> result.recentCandles().clear());
    }

    @Test void rejectsLookaheadEvenWhenDataIsOtherwiseValid() {
        var data = candles(30);
        assertThrows(IllegalArgumentException.class,
                () -> BUILDER.build(data, data.getLast().closeTime() - 1));
    }

    @Test void rejectsGapsInHistory() {
        var data = candles(30);
        data.remove(15);
        assertThrows(IllegalArgumentException.class,
                () -> BUILDER.build(data, data.getLast().closeTime()));
    }

    @Test void rejectsInsufficientHistory() {
        var data = candles(20);
        assertEquals(21, BUILDER.minimumCandles());
        assertThrows(IllegalArgumentException.class,
                () -> BUILDER.build(data, data.getLast().closeTime()));
    }

    @Test void rejectsOpenCandles() {
        var data = candles(30);
        var last = data.getLast();
        data.set(29, new MarketCandle(last.symbol(), last.interval(), last.openTime(), last.closeTime(),
                last.open(), last.high(), last.low(), last.close(), last.baseVolume(), last.quoteVolume(),
                last.numberOfTrades(), last.takerBuyBaseVolume(), last.takerBuyQuoteVolume(), false));
        assertThrows(IllegalArgumentException.class,
                () -> BUILDER.build(data, data.getLast().closeTime()));
    }

    @Test void permitsHistoricalCutoffLaterThanLatestClose() {
        var data = candles(30);
        var result = BUILDER.build(data, data.getLast().closeTime() + 40_000);
        assertEquals(data.getLast().closeTime(), result.asOfCloseTime());
    }

    static ArrayList<MarketCandle> candles(int count) {
        var result = new ArrayList<MarketCandle>();
        for (int i = 0; i < count; i++) {
            long open = i * 60_000L;
            BigDecimal p = BigDecimal.valueOf(100 + (i % 8));
            result.add(new MarketCandle("BTCUSDT", CandleInterval.M1, open, open + 59_999,
                    p, p.add(BigDecimal.TEN), p.subtract(BigDecimal.TEN), p.add(BigDecimal.ONE),
                    BigDecimal.valueOf(100 + i), BigDecimal.valueOf(10000 + i*100),
                    100+i, BigDecimal.valueOf(50), BigDecimal.valueOf(5000), true));
        }
        return result;
    }
}
