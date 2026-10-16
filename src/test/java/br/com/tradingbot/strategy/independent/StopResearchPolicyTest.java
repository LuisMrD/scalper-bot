package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** All cases operate on sequential CLOSED 15m candles, not future peaks. */
class StopResearchPolicyTest {
    private static final long EPOCH=1_800_000_000_000L;
    private static BigDecimal b(String s){return new BigDecimal(s);}
    private static MarketCandle bar(int index,String open,String high,String low,String close) {
        long at=EPOCH+index*CandleInterval.M15.millis();
        return new MarketCandle("BTCUSDT",CandleInterval.M15,at,
                at+CandleInterval.M15.millis()-1,b(open),b(high),b(low),
                b(close),b("1000"),b("100000"),120,b("500"),b("50000"),true);
    }
    private static List<MarketCandle> tape() {
        var data=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++)
            data.add(bar(i,"100","100.5","99.5","100"));
        return data;
    }
    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static IndependentResearchRequest request() {
        return new IndependentResearchRequest("BTCUSDT",2400,60,"15m","1d",
                EPOCH+3000*CandleInterval.M15.millis()-1,params(),
                b("1500"),b("0.5"),b("3"),1,
                b("0.05"),b("0.02"),b("0.01"),b("0"),b("0.5"),
                b("0.01"),b("0.001"),b("0.001"),b("5"),b("50"),30);
    }
    private static IndependentSetupSignal signal(boolean shortSide,String stop) {
        return new IndependentSetupSignal(shortSide
                ?IndependentSetupSignal.Setup.TREND_PULLBACK_SHORT
                :IndependentSetupSignal.Setup.TREND_PULLBACK,
                IndependentSetupSignal.Status.CONFIRMED,
                EPOCH+59*CandleInterval.M15.millis()+CandleInterval.M15.millis()-1,
                b("100"),b(stop),null,null,"FIXTURE",List.of("TEST"),List.of());
    }
    private static IndependentPaperPortfolio portfolio(boolean shortSide,
            StopResearchPolicy policy) {
        String arm=shortSide?"TREND_PULLBACK_SHORT":"TREND_PULLBACK";
        return new IndependentPaperPortfolio(arm+"__"+policy.name(),request(),policy);
    }
    private static void enter(IndependentPaperPortfolio paper,boolean shortSide,String stop) {
        paper.close(bar(59,"100","100.5","99.5","100"),
                signal(shortSide,stop),tape());
        paper.begin(bar(60,"100","100.5","99.5","100"));
        assertTrue(paper.summary(bar(60,"100","100.5","99.5","100")).openPosition());
    }
    private static IndependentPaperPortfolio.Trade stop(IndependentPaperPortfolio p,
            String high,String low) {
        p.begin(bar(61,"100",high,low,"100"));
        var result=p.summary(bar(61,"100",high,low,"100"));
        assertEquals(1,result.closedTrades());
        return result.trades().getFirst();
    }
    @Test void atrStopBufferKeepsExistingSignalButReducesPositionSize() {
        var baseline=portfolio(false,StopResearchPolicy.BASELINE);
        var quarter=portfolio(false,StopResearchPolicy.ATR_025);
        var half=portfolio(false,StopResearchPolicy.ATR_050);
        for(var p:List.of(baseline,quarter,half))enter(p,false,"99");
        var x=stop(baseline,"101","98");
        var y=stop(quarter,"101","98");
        var z=stop(half,"101","98");
        assertEquals(0,x.initialStop().compareTo(b("99")));
        assertEquals(0,y.initialStop().compareTo(b("98.75")));
        assertEquals(0,z.initialStop().compareTo(b("98.50")));
        assertTrue(x.quantity().compareTo(y.quantity())>=0);
        assertTrue(y.quantity().compareTo(z.quantity())>=0);
        assertEquals("STOP",z.reason());
    }
    @Test void atrStopForShortIsABOVETheStructuralHigh() {
        var quarter=portfolio(true,StopResearchPolicy.ATR_025);
        enter(quarter,true,"101");
        var closed=stop(quarter,"103","99");
        assertEquals(0,closed.initialStop().compareTo(b("101.25")));
        assertTrue(closed.netPnlUsdt().signum()<0);
    }
    @Test void longBreakEvenOnlyArmsAfterCompletedCloseAndIncludesBothFees() {
        var paper=portfolio(false,StopResearchPolicy.BREAK_EVEN_1_5R);
        enter(paper,false,"96");
        // +1.5R close is only known AFTER the bar has completed.
        var up=bar(61,"100","108","99.5","107");
        paper.begin(up);
        assertTrue(paper.summary(up).openPosition());
        paper.close(up,null,List.of(up));
        assertTrue(paper.summary(up).openPosition());
        // Stop move is effective only on NEXT completed 15m bar.
        var reversal=bar(62,"100.5","101","99.5","100.5");
        paper.begin(reversal);
        var t=paper.summary(reversal).trades().getFirst();
        assertEquals("STOP",t.reason());
        assertTrue(t.netPnlUsdt().signum()>=0,"BE must include commissions and adverse fills");
        assertTrue(t.mfeR().signum()>0);
        assertTrue(t.maeR().signum()>=0);
    }
    @Test void shortBreakEvenAlsoCoversBuybackSpreadAndCommissions() {
        var paper=portfolio(true,StopResearchPolicy.BREAK_EVEN_1_5R);
        enter(paper,true,"104");
        var fall=bar(61,"100","100.5","92","93");
        paper.begin(fall);
        paper.close(fall,null,List.of(fall));
        var reversal=bar(62,"99","101","98","99");
        paper.begin(reversal);
        var t=paper.summary(reversal).trades().getFirst();
        assertTrue(t.netPnlUsdt().signum()>=0,"Short BE must include buyback costs");
        assertEquals("STOP",t.reason());
    }
    @Test void atrTrailingOnlyTightensAfterCloseAndPreservesLossCap() {
        var paper=portfolio(false,StopResearchPolicy.ATR_TRAIL_2_5);
        enter(paper,false,"96");
        var advance=bar(61,"100","108","99.5","107");
        paper.begin(advance);
        paper.close(advance,null,List.of(advance));
        var pullback=bar(62,"106","107","104","105");
        paper.begin(pullback);
        var t=paper.summary(pullback).trades().getFirst();
        assertEquals("STOP",t.reason());
        assertTrue(t.netPnlUsdt().signum()>0,"Trailing must protect some favorable excursion");
        assertTrue(t.netR().signum()>0);
    }
    @Test void policyNamesAreFrozenAndClassicPortfolioIsStillBaseline() {
        assertEquals(List.of("BASELINE","ATR_025","ATR_050",
                        "BREAK_EVEN_1_5R","ATR_TRAIL_2_5"),
                java.util.Arrays.stream(StopResearchPolicy.values())
                        .map(Enum::name).toList());
        var classic=new IndependentPaperPortfolio("TREND_PULLBACK",request());
        var baseline=portfolio(false,StopResearchPolicy.BASELINE);
        for(var p:List.of(classic,baseline))enter(p,false,"99");
        var a=stop(classic,"101","98");
        var b=stop(baseline,"101","98");
        assertEquals(0,a.netPnlUsdt().compareTo(b.netPnlUsdt()));
        assertEquals(0,a.initialStop().compareTo(b.initialStop()));
    }
}
