package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LongContextComparisonServiceTest {
    private BacktestRequest request(boolean luna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m","15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void threeFrozenPoliciesUseThreeIndependentFullReplays() {
        var replay = mock(BacktestService.class);
        var req = request(false, false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (var policy : LongContextPolicy.values()) {
            when(replay.runLongContext(req, policy))
                    .thenReturn(new BacktestService.FilteredReplay(result,
                            3, 4, 0, 0, 0, 0, 0, 0, policy == LongContextPolicy.CONTROL ? 0 : 6));
        }
        var response = new LongContextComparisonService(replay).compare(req);
        assertEquals(3, response.summaries().size());
        assertEquals(LongContextPolicy.CONTROL, response.summaries().getFirst().policy());
        assertEquals(0, response.summaries().getFirst().longContextBlockedSignals());
        assertEquals(6, response.summaries().get(1).longContextBlockedSignals());
        assertEquals(6, response.summaries().get(2).longContextBlockedSignals());
        for (var policy : LongContextPolicy.values()) {
            verify(replay).runLongContext(req, policy);
        }
        verifyNoMoreInteractions(replay);
    }

    @Test
    void onlyRetestNoLunaAndCorrectTimeframesMayReachBacktests() {
        var backtests = mock(BacktestService.class);
        var service = new LongContextComparisonService(backtests);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(true,false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(false,true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
