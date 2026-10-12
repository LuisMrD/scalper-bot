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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** O replay deve reutilizar uma unica consulta e nunca incluir barras futuras. */
class PreparedMarketReplayTest {
    private static final long NOW = 1_900_000_000_000L;
    private static final long START = NOW - 40L * 60_000L;

    private MarketContextService service(MarketDataService market) {
        return new MarketContextService(market,
                new PriceActionConfiguration(20, 2, 2, 10, 20, .6, .25, .5),
                new VolumeConfiguration(20, 10, 1.8, .6, 1.5, .7, .6, .55, .75),
                new MarketLevelsConfiguration(14, .5, .1, 12),
                new MarketContextConfiguration(10),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    private static List<MarketCandle> candles() {
        var candles = new ArrayList<MarketCandle>();
        for (int i = 0; i < 37; i++) {
            long t = START + i * 60_000L;
            var open = BigDecimal.valueOf(100 + i % 5);
            candles.add(new MarketCandle("BTCUSDT", CandleInterval.M1, t, t + 59_999L,
                    open, open.add(BigDecimal.valueOf(4)),
                    open.subtract(BigDecimal.valueOf(4)), open.add(BigDecimal.ONE),
                    BigDecimal.valueOf(100 + i), BigDecimal.valueOf(10000 + i),
                    100, BigDecimal.valueOf(50), BigDecimal.valueOf(5000), true));
        }
        return candles;
    }

    @Test
    void cachesOnceAndUsesOnlyBarsClosedAtRequestedCutoff() {
        var market = mock(MarketDataService.class);
        var data = candles();
        var descending = new ArrayList<>(data);
        Collections.reverse(descending);
        long first = data.get(34).closeTime();
        long last = data.get(36).closeTime();
        when(market.recentAtOrBefore(eq("BTCUSDT"), eq("1m"), anyInt(), eq(last)))
                .thenReturn(descending);
        var replay = service(market).prepareReplay("BTCUSDT", List.of("1m"), 30, first, last);

        var a = replay.at(first).timeframes().getFirst();
        var b = replay.at(last).timeframes().getFirst();
        assertEquals(first, a.asOfCloseTime());
        assertEquals(last, b.asOfCloseTime());
        assertEquals(30, a.candlesAnalyzed());
        assertEquals(30, b.candlesAnalyzed());
        assertTrue(a.recentCandles().stream().allMatch(c -> c.closeTime() <= first));
        assertTrue(b.recentCandles().stream().allMatch(c -> c.closeTime() <= last));
        assertNotEquals(a.asOfCloseTime(), b.asOfCloseTime());
        verify(market, times(1)).recentAtOrBefore(eq("BTCUSDT"), eq("1m"), anyInt(), eq(last));
        verifyNoMoreInteractions(market);
    }

    @Test
    void missingClosedBarsReturns422RatherThanInventedMarketContext() {
        var market = mock(MarketDataService.class);
        var data = new ArrayList<>(candles());
        data.remove(25);
        var descending = new ArrayList<>(data);
        Collections.reverse(descending);
        long first = data.get(33).closeTime();
        long last = data.getLast().closeTime();
        when(market.recentAtOrBefore(eq("BTCUSDT"), eq("1m"), anyInt(), eq(last)))
                .thenReturn(descending);
        var replay = service(market).prepareReplay("BTCUSDT", List.of("1m"), 30, first, last);
        var ex = assertThrows(ResponseStatusException.class, () -> replay.at(last));
        assertEquals(422, ex.getStatusCode().value());
    }

    @Test
    void rejectsTimeframeCombinationsRequiringExcessiveHistoricalMaterial() {
        var market = mock(MarketDataService.class);
        long first = NOW - 70_000L * 60_000L;
        var ex = assertThrows(ResponseStatusException.class,
                () -> service(market).prepareReplay("BTCUSDT", List.of("1m"),
                        200, first, NOW - 1));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(market);
    }
}
