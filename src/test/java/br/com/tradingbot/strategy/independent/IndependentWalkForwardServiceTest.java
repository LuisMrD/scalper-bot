package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndependentWalkForwardServiceTest {
    private static BigDecimal n(String s) {return new BigDecimal(s);}
    private static final long BASE=1_800_000_299_999L;
    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,3,n("2"),
                n("0.9"),n("0.9"),n("0.6"),n("2"),
                n("1.1"),n("1.8"),8,n("3"),
                n("0.9"),n("0.9"),n("1.2"),5,n("0.5"),
                n("1"),14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,n("1"));
    }
    private static IndependentResearchRequest request(long at,BigDecimal fee) {
        return new IndependentResearchRequest("BTCUSDT",100,100,"5m","15m",at,params(),
                n("1000"),n("1"),n("3"),1,fee,
                n("0.02"),n("0.02"),n("0.00"),n("0"),
                n("0.01"),n("0.001"),n("0.001"),n("5"),n("50"),100);
    }
    @Test void nonOverlappingWindowsMandatory() {
        var backtest=mock(IndependentResearchService.class);
        var service=new IndependentWalkForwardService(backtest);
        assertThrows(IllegalArgumentException.class,()->service.compare(
                new IndependentWalkForwardService.Request(List.of(
                        request(BASE,n("0.05")),
                        request(BASE+10*300_000L,n("0.05"))))));
        verifyNoInteractions(backtest);
    }
    @Test void frozenStudyRejectsChangesInCostsWithinHoldout() {
        var backtest=mock(IndependentResearchService.class);
        var service=new IndependentWalkForwardService(backtest);
        assertThrows(IllegalArgumentException.class,()->service.compare(
                new IndependentWalkForwardService.Request(List.of(
                        request(BASE,n("0.05")),
                        request(BASE+100*300_000L,n("0.10"))))));
        verifyNoInteractions(backtest);
    }
    @Test void aggregatesIndependentTradeOutcomesWithoutReoptimization() {
        var backtest=mock(IndependentResearchService.class);
        var a=request(BASE,n("0.05"));
        var b=request(BASE+100*300_000L,n("0.05"));
        var names=List.of("TREND_PULLBACK","RANGE_BREAKOUT","RANGE_BREAKOUT_RETEST");
        for(var w:List.of(a,b)) {
            var summaries=new java.util.LinkedHashMap<String,IndependentPaperPortfolio.Summary>();
            for(var arm:names) {
                var trade=new IndependentPaperPortfolio.Trade(w.asOf()-300_000L,
                        w.asOf(),w.asOf()+300_000L,n("100"),n("101"),
                        n("99"),n("1"),n("0.88"),n("0.12"),n("0"),"STOP");
                summaries.put(arm,new IndependentPaperPortfolio.Summary(
                        arm,n("1000"),n("1000.88"),n("0.88"),
                        n("0.12"),n("0"),1,1,0,1,n("0.5"),false,
                        List.of(trade),Map.of()));
            }
            when(backtest.backtest(w)).thenReturn(new IndependentResearchService.Report(
                    w.asOf(),"BTCUSDT","5m",w.asOf()-100*300_000L+1,w.asOf(),
                    100,"PAPER_ONLY",Map.copyOf(summaries),List.of(),Map.of()));
        }
        var out=new IndependentWalkForwardService(backtest).compare(
                new IndependentWalkForwardService.Request(List.of(b,a)));
        assertEquals(2,out.windows().size());
        assertEquals(List.of(a.asOf(),b.asOf()),out.persistedRunIds());
        assertEquals(3,out.aggregate().size());
        var score=out.aggregate().getFirst();
        assertEquals(2,score.trades());
        assertEquals(2,score.wins());
        assertEquals(0,score.sumRealizedNetUsdt().compareTo(n("1.76")));
        verify(backtest).backtest(a);
        verify(backtest).backtest(b);
        verifyNoMoreInteractions(backtest);
    }
}
