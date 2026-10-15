package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BollingerComparisonServiceTest {
    private static BacktestRequest request(boolean luna) {
        return new BacktestRequest("SOLUSDT", List.of("5m","15m"),
                luna ? 3 : 3600, 200, 1791845999999L, luna,
                new BigDecimal("0.01"),new BigDecimal("0.001"),
                new BigDecimal("0.001"),new BigDecimal("5"),
                BigDecimal.ONE,new BigDecimal("50"),null,false,true,false);
    }

    @Test
    void comparesIndependentUnchangedControlAndBbSideBySide() {
        var service=mock(BacktestService.class);
        var request=request(false);
        var result1=mock(BacktestResult.class);
        var result2=mock(BacktestResult.class);
        var p1=mock(BacktestResult.PortfolioResult.class);
        var p2=mock(BacktestResult.PortfolioResult.class);
        long from=1791845999999L-3600L*300000L+1;
        for(var result:List.of(result1,result2)){
            when(result.replayedCandles()).thenReturn(3600);
            when(result.intervals()).thenReturn(List.of("5m","15m"));
            when(result.fromCandleOpenTime()).thenReturn(from);
            when(result.throughCandleCloseTime()).thenReturn(1791845999999L);
        }
        when(result1.javaPortfolio()).thenReturn(p1);
        when(result2.javaPortfolio()).thenReturn(p2);
        for(var portfolio:List.of(p1,p2)){
            when(portfolio.trades()).thenReturn(List.of());
            when(portfolio.startingEquityUsdt()).thenReturn(new BigDecimal("1500"));
            when(portfolio.markedEquityUsdt()).thenReturn(new BigDecimal("1500"));
        }
        when(service.runFiltered(request, EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS))
                .thenReturn(new BacktestService.FilteredReplay(result1,2,4));
        when(service.runBollingerStudy(request)).thenReturn(
                new BacktestService.BollingerReplay(
                    new BacktestService.FilteredReplay(result2,2,4),
                    17,3,2,Map.of()));
        var response=new BollingerComparisonService(service).compare(request);
        assertEquals(2,response.summaries().size());
        assertEquals(BollingerComparisonService.Arm.CONTROL,response.summaries().getFirst().arm());
        assertEquals(0,response.summaries().getFirst().outsideBandCloses());
        assertEquals(17,response.summaries().get(1).outsideBandCloses());
        assertEquals(3,response.summaries().get(1).bbCooldownRejected());
        assertEquals(20,response.bollingerPeriod());
        assertEquals(0,response.deviations().compareTo(new BigDecimal("2")));
        verify(service).runFiltered(request,EntryBreakoutFilter.BLOCK_BOTH,
                ReentryWaitPolicy.WAIT_12_BARS);
        verify(service).runBollingerStudy(request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void paidLunaIsRejectedBeforeAnyPortfolioOrNetworkCall() {
        var service=mock(BacktestService.class);
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->new BollingerComparisonService(service).compare(request(true)))
                .getStatusCode().value());
        verifyNoInteractions(service);
    }
}
