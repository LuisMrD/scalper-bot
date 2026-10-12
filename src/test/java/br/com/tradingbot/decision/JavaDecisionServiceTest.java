package br.com.tradingbot.decision;

import br.com.tradingbot.analysis.context.MarketContextService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JavaDecisionServiceTest {
    @Test void loadsSharedContextOnceAndPreservesAsOfWithoutOrders() {
        var contextService = mock(MarketContextService.class);
        var frames = List.of("5m");
        var candleData = JavaDecisionEngineTest.base(
                br.com.tradingbot.market.model.CandleInterval.M5, 15, 100, 101, 105, 99);
        var context = JavaDecisionEngineTest.context(candleData,
                br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend.RANGE,
                br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel.NORMAL,
                br.com.tradingbot.analysis.volume.VolumeSnapshot.TakerBalance.BALANCED,
                true, List.of());
        var multi = JavaDecisionEngineTest.multi(context);
        when(contextService.analyzeMultiple("BTCUSDT", frames, 200, 300_000_000L)).thenReturn(multi);
        var config = new JavaDecisionConfiguration(3, 4, 2, true, true, true, true);
        var service = new JavaDecisionService(contextService, config);
        var decision = service.analyze("BTCUSDT", frames, 200, 300_000_000L, PositionState.Side.FLAT);
        assertEquals(DecisionResult.Entry.WAIT, decision.entry());
        assertEquals(300_000_000L, decision.requestedAsOfEpochMs());
        verify(contextService, times(1)).analyzeMultiple("BTCUSDT", frames, 200, 300_000_000L);
        verifyNoMoreInteractions(contextService);
    }
}
