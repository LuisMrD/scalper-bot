package br.com.tradingbot.analysis.context;

import br.com.tradingbot.analysis.brooks.PriceActionConfiguration;
import br.com.tradingbot.analysis.levels.MarketLevelsConfiguration;
import br.com.tradingbot.analysis.volume.VolumeConfiguration;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketContextServiceTest {
    private static final long NOW = 20_000_000L;
    private static final PriceActionConfiguration BROOKS =
            new PriceActionConfiguration(20, 2, 2, 10, 20, .6, .25, .5);
    private static final VolumeConfiguration VOLUME =
            new VolumeConfiguration(20, 10, 1.8, .6, 1.5, .7, .6, .55, .75);
    private static final MarketLevelsConfiguration LEVELS =
            new MarketLevelsConfiguration(14, .5, .1, 12);

    private static MarketContextService service(MarketDataService market) {
        return new MarketContextService(market, BROOKS, VOLUME, LEVELS,
                new MarketContextConfiguration(10), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test void queriesOnlyOncePerFrameAndRestoresAscendingOrder() {
        var market = mock(MarketDataService.class);
        var descending = history(CandleInterval.M1, 30);
        Collections.reverse(descending);
        when(market.recentAtOrBefore("BTCUSDT", "1m", 30, NOW)).thenReturn(descending);
        var context = service(market).analyze("BTCUSDT", "1m", 30, null);
        assertEquals(NOW, context.requestedAsOfEpochMs());
        assertEquals(30, context.candlesAnalyzed());
        assertEquals(10, context.recentCandles().size());
        verify(market, times(1)).recentAtOrBefore("BTCUSDT", "1m", 30, NOW);
        verifyNoMoreInteractions(market);
    }

    @Test void preservesSingleCutoffForMultipleIntervals() {
        var market = mock(MarketDataService.class);
        for (var tf : List.of(CandleInterval.M1, CandleInterval.M5)) {
            var data = history(tf, 30);
            Collections.reverse(data);
            when(market.recentAtOrBefore("BTCUSDT", tf.code(), 30, NOW)).thenReturn(data);
        }
        var result = service(market).analyzeMultiple("BTCUSDT", List.of("1m", "5m"), 30, null);
        assertEquals(2, result.timeframes().size());
        assertEquals(NOW, result.requestedAsOfEpochMs());
        assertEquals(NOW, result.timeframes().get(0).requestedAsOfEpochMs());
        assertEquals(NOW, result.timeframes().get(1).requestedAsOfEpochMs());
        verify(market, times(1)).recentAtOrBefore("BTCUSDT", "1m", 30, NOW);
        verify(market, times(1)).recentAtOrBefore("BTCUSDT", "5m", 30, NOW);
    }

    @Test void asOfIsPassedIntoHistoricalDbQueryUnchanged() {
        var market = mock(MarketDataService.class);
        var data = history(CandleInterval.M1, 30);
        Collections.reverse(data);
        long historicalCutoff = NOW - 1;
        when(market.recentAtOrBefore("BTCUSDT", "1m", 30, historicalCutoff)).thenReturn(data);
        var context = service(market).analyze("BTCUSDT", "1m", 30, historicalCutoff);
        assertEquals(historicalCutoff, context.requestedAsOfEpochMs());
        verify(market).recentAtOrBefore("BTCUSDT", "1m", 30, historicalCutoff);
    }

    @Test void rejectsFutureCutoffBeforeLoadingData() {
        var market = mock(MarketDataService.class);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service(market).analyze("BTCUSDT", "1m", 30, NOW+1));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(market);
    }

    @Test void rejectsRepeatedIntervalsBeforeLoadingData() {
        var market = mock(MarketDataService.class);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service(market).analyzeMultiple("BTCUSDT", List.of("1m", "1m"), 30, null));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(market);
    }

    @Test void rejectsUnsupportedIntervalsBeforeLoadingData() {
        var market = mock(MarketDataService.class);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service(market).analyze("BTCUSDT", "5s", 30, null));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(market);
    }

    @Test void rejectsShortLimitBeforeLoadingData() {
        var market = mock(MarketDataService.class);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service(market).analyze("BTCUSDT", "1m", 20, null));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(market);
    }

    @Test void incompleteDataReturns422RatherThanPartialAnalysis() {
        var market = mock(MarketDataService.class);
        when(market.recentAtOrBefore("BTCUSDT", "1m", 30, NOW)).thenReturn(List.of());
        var ex = assertThrows(ResponseStatusException.class,
                () -> service(market).analyze("BTCUSDT", "1m", 30, null));
        assertEquals(422, ex.getStatusCode().value());
    }

    static ArrayList<MarketCandle> history(CandleInterval interval, int count) {
        var list = new ArrayList<MarketCandle>();
        // Os 30 candles 5m terminam apos NOW. Para teste de recuperacao historica,
        // criar a janela mais antiga, inteiramente anterior a NOW.
        long start = NOW - (long) (count + 1)*interval.millis();
        for (int i = 0; i < count; i++) {
            long begin = start + (long) i*interval.millis();
            var p = BigDecimal.valueOf(100 + i%8);
            list.add(new MarketCandle("BTCUSDT", interval, begin, begin+interval.millis()-1,
                    p, p.add(BigDecimal.TEN), p.subtract(BigDecimal.TEN), p.add(BigDecimal.ONE),
                    BigDecimal.valueOf(100+i), BigDecimal.valueOf(10000+i*100),
                    100+i, BigDecimal.valueOf(50), BigDecimal.valueOf(5000), true));
        }
        return list;
    }
}
