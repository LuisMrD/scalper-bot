package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndependentDiagnosticIterationTest {
    private static final long BEGIN = 1_800_000_000_000L;
    private static BigDecimal b(double x) {return BigDecimal.valueOf(x);}
    private static BigDecimal b(String x) {return new BigDecimal(x);}
    private static IndependentSetupParameters parameters() {
        return new IndependentSetupParameters(2,2,3,3,b("2"),
                b("0.9"),b("0.9"),b("0.6"),b("2"),
                b("1.1"),b("1.8"),8,b("3"),
                b("0.9"),b("0.9"),b("1.2"),5,b("0.5"),b("1"),
                14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static MarketCandle bar(int i,double open,double high,double low,
            double close,double volume,double buy) {
        long t=BEGIN+i*CandleInterval.M5.millis();
        return new MarketCandle("BTCUSDT",CandleInterval.M5,t,
                t+CandleInterval.M5.millis()-1,b(open),b(high),b(low),
                b(close),b(volume),b(volume),100,b(buy),b(buy),true);
    }
    private static ArrayList<MarketCandle> trend() {
        var tape=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++) {
            double v=80+i*0.32+1.7*Math.sin(2*Math.PI*(i%8)/8.0);
            tape.add(bar(i,v-0.5,v+0.5,v-1,v,1000,600));
        }
        return tape;
    }
    private static List<MarketCandle> mixedRetreat() {
        var t=trend();
        t.add(bar(60,99.8,100,99.1,99.4,700,200));
        t.add(bar(61,98.4,99,98.1,98.7,600,190)); // bullish pause
        t.add(bar(62,98,98.2,97.3,97.6,500,150));
        t.add(bar(63,97.7,99.7,97.5,99.5,1300,900));
        return t;
    }
    @Test
    void mixedBullishPauseBlocksLegacyButMayPassIndependentNetDeclineArm() {
        var tape=mixedRetreat();
        var original=new TrendPullbackDetector(parameters()).evaluate(tape,b("0.01"));
        var mixed=new TrendPullbackDetector(parameters(),
                TrendPullbackDetector.RetreatMode.MIXED_NET_DECLINE)
                .evaluate(tape,b("0.01"));
        assertFalse(original.actionable());
        assertEquals(IndependentSetupSignal.Setup.TREND_PULLBACK,original.setup());
        assertTrue(original.rejectedFilters().contains("PULLBACK_NOT_ORDERLY_BEARISH"),
                original.rejectedFilters().toString());
        assertEquals(IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED,mixed.setup());
        assertTrue(mixed.actionable(),mixed.rejectedFilters().toString());
        assertTrue(mixed.passedFilters().contains("MIXED_PULLBACK_NET_DECLINE"));
        assertEquals(0,mixed.structuralStop().compareTo(b("97.29")));
    }
    @Test
    void mixedVariantDoesNotAcceptSidewaysOrNetRisingSequence() {
        var tape=new ArrayList<>(mixedRetreat());
        tape.set(62,bar(62,99.1,100.2,98.7,99.9,500,150));
        var mixed=new TrendPullbackDetector(parameters(),
                TrendPullbackDetector.RetreatMode.MIXED_NET_DECLINE)
                .evaluate(tape,b("0.01"));
        assertFalse(mixed.actionable());
        assertTrue(mixed.rejectedFilters().contains("MIXED_PULLBACK_NO_NET_DECLINE"),
                mixed.rejectedFilters().toString());
    }
    @Test
    void boxExplainsSeveralSimultaneousFailuresRatherThanOneOpaqueRejection() {
        var tape=trend();
        for(int i=60;i<68;i++)
            tape.add(bar(i,103,110,90,105,800+100*(i-60),500));
        tape.add(bar(68,105,108,104,107,1800,1200));
        var breakout=new RangeBreakoutDetector(parameters(),
                RangeBreakoutDetector.Variant.IMMEDIATE).onClosed(tape,b("0.01"));
        assertFalse(breakout.actionable());
        assertTrue(breakout.rejectedFilters().contains("BOX_RANGE_NOT_CONTRACTING"),
                breakout.rejectedFilters().toString());
        assertTrue(breakout.rejectedFilters().contains("BOX_VOLUME_NOT_DRYING"));
        assertNotEquals("NO_PRECONFIRMED_NARROW_DRY_BOX",breakout.reason());
    }
    @Test
    void explicitTighterAtrWidthReportsBoxTooWideWithoutChangingStrategyDefaults() {
        // This is a TEST-FIXTURE threshold, not a video specification.
        var p=new IndependentSetupParameters(2,2,3,3,b("2"),
                b("0.9"),b("0.9"),b("0.6"),b("2"),
                b("1.1"),b("1.8"),8,b("0.5"),
                b("0.9"),b("0.9"),b("1.2"),5,b("0.5"),b("1"),
                14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
        var tape=trend();
        for(int i=60;i<68;i++)
            tape.add(bar(i,103,110,90,105,800+100*(i-60),500));
        tape.add(bar(68,105,108,104,107,1800,1200));
        var signal=new RangeBreakoutDetector(p,
                RangeBreakoutDetector.Variant.IMMEDIATE).onClosed(tape,b("0.01"));
        assertTrue(signal.rejectedFilters().contains("BOX_WIDTH_TOO_WIDE"),
                signal.rejectedFilters().toString());
    }

    @Test
    void invalidatedEventDoesNotCountTheSameUnderlyingFilterTwice() {
        var account=new IndependentPaperPortfolio("TREND_PULLBACK",
                new IndependentResearchRequest("BTCUSDT",100,100,"5m","15m",
                BEGIN+100*CandleInterval.M5.millis()-1,parameters(),
                b("1000"),b("0.5"),b("3"),1,b("0.05"),b("0.02"),
                b("0"),b("0"),b("0.5"),b("0.01"),b("0.001"),
                b("0.001"),b("5"),b("50"),30));
        var invalidated=new IndependentSetupSignal(
                IndependentSetupSignal.Setup.TREND_PULLBACK,
                IndependentSetupSignal.Status.INVALIDATED,BEGIN,null,null,null,
                null,"STRUCTURAL_LOW_BROKEN",List.of(),
                List.of("STRUCTURAL_LOW_BROKEN","STRUCTURAL_LOW_BROKEN","EMA_ALIGNMENT"));
        account.count(invalidated);
        assertEquals(1,account.summary(bar(80,100,101,99,100,1000,600))
                .rejectionCounts().get("STRUCTURAL_LOW_BROKEN"));
        assertEquals(1,account.summary(bar(80,100,101,99,100,1000,600))
                .rejectionCounts().get("EMA_ALIGNMENT"));
    }
}
