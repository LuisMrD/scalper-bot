package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ShortRegimeDiagnosticsServiceTest {
    private BacktestRequest request(boolean luna, boolean pullback) {
        return new BacktestRequest("SOLUSDT", List.of("5m","15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("5"),
                BigDecimal.ONE, new BigDecimal("50"), null, pullback, true);
    }

    @Test
    void controlAndShortOnlyMustUseIndependentReplaysWithoutMacro() {
        var replay = mock(BacktestService.class);
        var input = request(false,false);
        var result = mock(BacktestResult.class);
        var p = mock(BacktestResult.PortfolioResult.class);
        when(result.fromCandleOpenTime()).thenReturn(1790766000000L);
        when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        when(result.replayedCandles()).thenReturn(3600);
        when(result.javaPortfolio()).thenReturn(p);
        when(p.trades()).thenReturn(List.of());
        when(p.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(p.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        when(replay.runLongVersusShort(input, LongVersusShortPolicy.CONTROL))
                .thenReturn(new BacktestService.FilteredReplay(result, 7, 3, 0, 0));
        when(replay.runLongVersusShort(input, LongVersusShortPolicy.SHORT_ONLY))
                .thenReturn(new BacktestService.FilteredReplay(result, 7, 3, 0, 6));
        var report = new ShortRegimeDiagnosticsService(replay).analyze(input);
        assertEquals(2, report.summaries().size());
        assertEquals(LongVersusShortPolicy.CONTROL, report.summaries().getFirst().policy());
        assertEquals(LongVersusShortPolicy.SHORT_ONLY, report.summaries().get(1).policy());
        assertEquals(0, report.summaries().getFirst().shortOnlyBlockedLongSignals());
        assertEquals(6, report.summaries().get(1).shortOnlyBlockedLongSignals());
        assertEquals(0, report.summaries().get(1).longTrades());
        assertTrue(report.trades().isEmpty());
        verify(replay).runLongVersusShort(input, LongVersusShortPolicy.CONTROL);
        verify(replay).runLongVersusShort(input, LongVersusShortPolicy.SHORT_ONLY);
        verifyNoMoreInteractions(replay);
    }

    @Test
    void rejectsPaidOrIncorrectSetupsBeforeAccessingMarket() {
        var replay = mock(BacktestService.class);
        var service = new ShortRegimeDiagnosticsService(replay);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.analyze(request(true,false))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.analyze(request(false,true))).getStatusCode().value());
        verifyNoInteractions(replay);
    }

    @Test
    void responseSchemaPreservesInputOutputSeparationForResearch() {
        var entryNames = java.util.Arrays.stream(
                ShortRegimeDiagnosticsService.Trade.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertTrue(entryNames.indexOf("trend15m") < entryNames.indexOf("exitReason"));
        assertTrue(entryNames.indexOf("stopDistancePct") < entryNames.indexOf("exitReason"));
        assertTrue(entryNames.indexOf("mfeR") > entryNames.indexOf("exitReason"));
        assertTrue(entryNames.indexOf("givebackFromBestCloseUsdt") >
                entryNames.indexOf("exitReason"));
    }
}
