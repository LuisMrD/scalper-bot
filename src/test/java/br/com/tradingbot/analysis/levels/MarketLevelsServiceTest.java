package br.com.tradingbot.analysis.levels;

import br.com.tradingbot.analysis.brooks.PriceActionConfiguration;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketLevelsServiceTest {
    private static final MarketLevelsConfiguration LEVELS =
            new MarketLevelsConfiguration(14, .5, .1, 12);
    private static final PriceActionConfiguration BROOKS =
            new PriceActionConfiguration(20, 2, 2, 10, 20, .6, .25, .5);

    @Test void reversesDatabaseDescendingOrder() {
        var market = mock(MarketDataService.class);
        var candles = history(30);
        Collections.reverse(candles);
        when(market.recent("BTCUSDT", "1m", 30)).thenReturn(candles);
        var actual = new MarketLevelsService(market, LEVELS, BROOKS).analyze("BTCUSDT", "1m", 30);
        assertEquals(29 * 60000L + 59999L, actual.asOfCloseTime());
        assertEquals(30, actual.candlesAnalyzed());
        verify(market).recent("BTCUSDT", "1m", 30);
    }

    @Test void limitBelowRequiredReturns400() {
        var market = mock(MarketDataService.class);
        var ex = assertThrows(ResponseStatusException.class,
                () -> new MarketLevelsService(market, LEVELS, BROOKS).analyze("BTCUSDT", "1m", 19));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(market);
    }

    @Test void unavailableHistoryReturns422() {
        var market = mock(MarketDataService.class);
        when(market.recent("BTCUSDT", "1m", 30)).thenReturn(history(10));
        var ex = assertThrows(ResponseStatusException.class,
                () -> new MarketLevelsService(market, LEVELS, BROOKS).analyze("BTCUSDT", "1m", 30));
        assertEquals(422, ex.getStatusCode().value());
    }

    private static ArrayList<MarketCandle> history(int count) {
        var candles = new ArrayList<MarketCandle>();
        for (int i = 0; i < count; i++) {
            long open = i * 60000L;
            candles.add(new MarketCandle("BTCUSDT", CandleInterval.M1, open, open + 59999,
                    BigDecimal.valueOf(105), BigDecimal.valueOf(110), BigDecimal.valueOf(100),
                    BigDecimal.valueOf(105), BigDecimal.valueOf(100), BigDecimal.valueOf(10000),
                    10, BigDecimal.valueOf(50), BigDecimal.valueOf(5000), true));
        }
        return candles;
    }
}
