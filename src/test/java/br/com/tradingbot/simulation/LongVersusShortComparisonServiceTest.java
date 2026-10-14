package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LongVersusShortComparisonServiceTest {
    private static BacktestRequest request(boolean luna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m", "15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void fourArmsUseFourIndependentBacktestInvocationsAndPreserveControl() {
        var svc = mock(BacktestService.class);
        var request = request(false,false);
        var result = mock(BacktestResult.class);
        var portfolio = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(portfolio);
        when(portfolio.trades()).thenReturn(List.of());
        when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        for (var policy : LongVersusShortPolicy.values()) {
            when(svc.runLongVersusShort(request, policy))
                    .thenReturn(new BacktestService.FilteredReplay(result, 6, 2,
                            policy.macro().active() ? 3 : 0,
                            policy.shortOnly() ? 4 : 0));
        }
        var report = new LongVersusShortComparisonService(svc).compare(request);
        assertEquals(4, report.summaries().size());
        assertEquals(LongVersusShortPolicy.CONTROL, report.summaries().getFirst().policy());
        assertEquals(LongVersusShortPolicy.SHORT_ONLY, report.summaries().get(1).policy());
        assertEquals(0, report.summaries().getFirst().macroBlockedLongSignals());
        assertEquals(4, report.summaries().get(1).shortOnlyBlockedLongSignals());
        assertEquals(3, report.summaries().get(2).macroBlockedLongSignals());
        for (var policy : LongVersusShortPolicy.values()) {
            verify(svc).runLongVersusShort(request, policy);
        }
        verifyNoMoreInteractions(svc);
    }

    @Test
    void unsafeRequestsAreRejectedBeforeMarketReplays() {
        var backtests = mock(BacktestService.class);
        var service = new LongVersusShortComparisonService(backtests);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(true, false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.compare(request(false, true))).getStatusCode().value());
        verifyNoInteractions(backtests);
    }

    @Test
    void onlyShortArmDoesNotUseMacroButPreservesExistingSixtyMinuteWait() {
        assertTrue(LongVersusShortPolicy.SHORT_ONLY.shortOnly());
        assertEquals(MagicMacroLongPolicy.NONE, LongVersusShortPolicy.SHORT_ONLY.macro());
        assertFalse(LongVersusShortPolicy.CONTROL.shortOnly());
        assertFalse(LongVersusShortPolicy.BOTH_UP_6H_DIP_3H.shortOnly());
        assertFalse(LongVersusShortPolicy.BOTH_UP_9H_DIP_3H.shortOnly());
        assertEquals(MagicMacroLongPolicy.BOTH_UP_6H_DIP_3H,
                LongVersusShortPolicy.BOTH_UP_6H_DIP_3H.macro());
        assertEquals(MagicMacroLongPolicy.BOTH_UP_9H_DIP_3H,
                LongVersusShortPolicy.BOTH_UP_9H_DIP_3H.macro());
    }
}
