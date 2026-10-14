package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class L2RangeComparisonServiceTest {
    private static BacktestRequest request(boolean luna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void evaluatesThreeIndependentDynamicPortfoliosAndAuditsL2Rejections() {
        var engine = mock(BacktestService.class);
        var req = request(false,false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (var arm : L2RangePolicy.values()) {
            when(engine.runL2Range(req, arm))
                    .thenReturn(new BacktestService.FilteredReplay(result, 4, 3, 0, 0,
                            arm == L2RangePolicy.CONTROL ? 0 : 2,
                            arm == L2RangePolicy.REPLACE_RANGE_SHORT_RETEST_WITH_L2 ? 3 : 0,
                            arm == L2RangePolicy.REPLACE_RANGE_SHORT_RETEST_WITH_L2 ? 1 : 0));
        }
        var report = new L2RangeComparisonService(engine).compare(req);
        assertEquals(3, report.summaries().size());
        assertEquals(L2RangePolicy.CONTROL, report.summaries().getFirst().policy());
        assertEquals(0, report.summaries().getFirst().l2Signals());
        assertEquals(3, report.summaries().get(2).l2Signals());
        assertEquals(1, report.summaries().get(2).l2BlockedByCooldown());
        for (var arm : L2RangePolicy.values()) verify(engine).runL2Range(req, arm);
        verifyNoMoreInteractions(engine);
    }

    @Test
    void unsafeRequestsAreRejectedBeforeAnyReplay() {
        var backtests = mock(BacktestService.class);
        var service = new L2RangeComparisonService(backtests);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(true,false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(false,true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }
}
