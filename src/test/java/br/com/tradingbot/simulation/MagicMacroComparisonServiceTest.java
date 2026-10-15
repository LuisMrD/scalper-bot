package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MagicMacroComparisonServiceTest {
    private static BacktestRequest request(boolean luna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void sixDynamicScenariosAndUnchangedBasePolicy() {
        var svc = mock(BacktestService.class);
        var input = request(false,false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (MagicMacroLongPolicy p : MagicMacroLongPolicy.values()) {
            when(svc.runFiltered(input, EntryBreakoutFilter.BLOCK_BOTH,
                    ReentryWaitPolicy.WAIT_12_BARS, p))
                    .thenReturn(new BacktestService.FilteredReplay(result, 4, 3,
                            p == MagicMacroLongPolicy.NONE ? 0 : 2));
        }
        var report = new MagicMacroComparisonService(svc).compare(input);
        assertEquals(6, report.summaries().size());
        assertEquals(MagicMacroLongPolicy.NONE, report.summaries().getFirst().macroPolicy());
        assertEquals(0, report.summaries().getFirst().macroBlockedLongSignals());
        for (var p : MagicMacroLongPolicy.values()) {
            verify(svc).runFiltered(input, EntryBreakoutFilter.BLOCK_BOTH,
                    ReentryWaitPolicy.WAIT_12_BARS, p);
        }
        verifyNoMoreInteractions(svc);
    }

    @Test
    void invalidMacroSetupIsRejectedWithoutRunningBacktest() {
        var svc = mock(BacktestService.class);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new MagicMacroComparisonService(svc).compare(request(true, false)))
                .getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> new MagicMacroComparisonService(svc).compare(request(false, true)))
                .getStatusCode().value());
        verifyNoInteractions(svc);
    }
}
