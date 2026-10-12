package br.com.tradingbot.analysis.brooks;

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

class PriceActionServiceTest {
    private static final PriceActionConfiguration CFG =
            new PriceActionConfiguration(20,2,2,10,20,.6,.25,.5);

    @Test void reversesRepositoryOrderBeforeAnalyzing() {
        var market = mock(MarketDataService.class);
        var list = new ArrayList<MarketCandle>();
        for(int i=0;i<20;i++) list.add(candle(i));
        Collections.reverse(list);
        when(market.recent("BTCUSDT", "1m", 20)).thenReturn(list);
        var result = new PriceActionService(market, CFG).analyze("BTCUSDT", "1m", 20);
        assertEquals(19 * 60_000L + 59_999L, result.asOfCloseTime());
        verify(market).recent("BTCUSDT", "1m", 20);
    }

    @Test void missingCandlesReturnUnprocessableEntity() {
        var market = mock(MarketDataService.class);
        when(market.recent("BTCUSDT", "1m", 20)).thenReturn(java.util.List.of());
        var ex = assertThrows(ResponseStatusException.class,
                () -> new PriceActionService(market, CFG).analyze("BTCUSDT", "1m", 20));
        assertEquals(422, ex.getStatusCode().value());
    }

    private static MarketCandle candle(int i) {
        return new MarketCandle("BTCUSDT", CandleInterval.M1, i * 60_000L,
                i * 60_000L + 59_999L, BigDecimal.valueOf(100), BigDecimal.valueOf(101),
                BigDecimal.valueOf(99), BigDecimal.valueOf(100), BigDecimal.ONE,
                BigDecimal.ONE, 1L, BigDecimal.ONE, BigDecimal.ONE, true);
    }
}
