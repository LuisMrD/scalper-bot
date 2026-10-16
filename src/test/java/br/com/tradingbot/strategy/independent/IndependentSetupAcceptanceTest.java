package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Acceptance IDs are from analise_setups_video_Nd7cCEIhRy0.md §9.
 * Explicit numerical values below are TEST FIXTURES, not presenter defaults.
 */
class IndependentSetupAcceptanceTest {
    private static final long BASE=1_800_000_000_000L;
    private static BigDecimal bd(String s) {return new BigDecimal(s);}
    private static BigDecimal bd(double n) {return BigDecimal.valueOf(n);}
    private static final BigDecimal TICK=bd("0.01");
    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,3,bd("2"),
                bd("0.9"),bd("0.9"),bd("0.6"),bd("2"),
                bd("1.1"),bd("1.8"),8,bd("3"),
                bd("0.9"),bd("0.9"),bd("1.2"),5,bd("0.5"),bd("1"),
                14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,bd("1"));
    }
    private static MarketCandle bar(int i,double open,double high,
            double low,double close,double volume,double takerBuy) {
        long at=BASE+i*CandleInterval.M5.millis();
        return new MarketCandle("BTCUSDT",CandleInterval.M5,at,
                at+CandleInterval.M5.millis()-1,bd(open),bd(high),bd(low),
                bd(close),bd(volume),bd(volume),100,bd(takerBuy),bd(takerBuy),true);
    }
    private static List<MarketCandle> trend() {
        var bars=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++) {
            double v=80+i*0.32+1.7*Math.sin(2*Math.PI*(i%8)/8.0);
            bars.add(bar(i,v-0.5,v+0.5,v-1.0,v,1000,600));
        }
        return bars;
    }
    private static List<MarketCandle> pullback(int variant) {
        var bars=trend();
        bars.add(bar(60,99.8,100.0,99.1,99.4,700,200));
        bars.add(bar(61,99.0,99.1,98.2,98.6,600,190));
        bars.add(bar(62,98.0,98.2,97.3,97.6,500,150));
        switch(variant) {
            case 1 -> bars.add(bar(63,97.6,97.7,97.2,97.4,500,100));
            case 2 -> bars.add(bar(63,97.7,99.7,97.5,99.5,1300,900));
            case 3 -> bars.add(bar(63,97.75,98.25,96.7,98.1,1300,900));
            case 4 -> bars.add(bar(63,98.3,99.0,98.2,98.45,1300,900));
            case 5 -> bars.add(bar(63,98.0,98.2,94.5,95.0,2500,200));
            case 6 -> bars.add(bar(63,97.7,97.8,95.8,96.0,900,200));
            case 7 -> bars.add(bar(63,97.7,97.8,93.0,93.5,900,200));
            default -> throw new IllegalArgumentException();
        }
        return bars;
    }
    private static List<MarketCandle> box() {
        var bars=trend();
        for(int i=0;i<8;i++) {
            double center=100.3+0.015*i,range=0.75-0.06*i;
            bars.add(bar(60+i,center-0.10,center+range/2,
                    center-range/2,center+0.10,900-65*i,540-40*i));
        }
        return bars;
    }
    private static MarketCandle breakout(int kind) {
        return switch(kind) {
            case 1 -> bar(68,100.5,100.7,100.2,100.6,1100,600);
            case 2 -> bar(68,100.5,101.1,100.3,100.6,1600,900);
            case 3 -> bar(68,100.7,101.5,100.6,101.2,600,400);
            case 4 -> bar(68,100.7,101.5,100.6,101.2,1800,1100);
            default -> throw new IllegalArgumentException();
        };
    }
    private static IndependentSetupSignal pull(int kind) {
        return new TrendPullbackDetector(params()).evaluate(pullback(kind),TICK);
    }
    private static IndependentSetupSignal breakOnce(int kind) {
        var tape=box();
        tape.add(breakout(kind));
        return new RangeBreakoutDetector(params(),
                RangeBreakoutDetector.Variant.IMMEDIATE).onClosed(tape,TICK);
    }
    @Test void A01_doesNotBuySolelyOnAverageTouchWithoutBuyerResponse() {
        assertFalse(pull(1).actionable());
    }
    @Test void A02_strongBuyerCandleAfterDryingPullbackIsEligible() {
        var s=pull(2);
        assertTrue(s.actionable(),s.rejectedFilters().toString());
        assertTrue(s.passedFilters().contains("BULLISH_STRENGTH"));
    }
    @Test void A03_rejectionHammerIsIndependentTrigger() {
        var s=pull(3);
        assertTrue(s.actionable(),s.rejectedFilters().toString());
        assertTrue(s.passedFilters().contains("LOWER_WICK_REJECTION"));
    }
    @Test void A04_closeAbovePreviousHighIsThirdTrigger() {
        var s=pull(4);
        assertTrue(s.actionable(),s.rejectedFilters().toString());
        assertTrue(s.passedFilters().contains("PREVIOUS_HIGH_CLOSE_BREAK"));
    }
    @Test void A05_abnormalSellingRejects() {
        var s=pull(5);
        assertFalse(s.actionable());
        assertEquals(IndependentSetupSignal.Status.INVALIDATED,s.status());
        assertTrue(s.rejectedFilters().contains("ABNORMAL_SELLING_VOLUME"));
    }
    @Test void A06_dominantBearishBarInvalidates() {
        var s=pull(6);
        assertEquals(IndependentSetupSignal.Status.INVALIDATED,s.status());
        assertTrue(s.rejectedFilters().contains("DOMINANT_BEARISH_CANDLE"));
    }
    @Test void A07_previousSwingLowLostInvalidates() {
        var s=pull(7);
        assertEquals(IndependentSetupSignal.Status.INVALIDATED,s.status());
        assertTrue(s.rejectedFilters().contains("STRUCTURAL_LOW_BROKEN"));
    }
    @Test void A08_trailingNeverWidensUnderLaterLowerPivot() {
        assertEquals(0,bd("99").compareTo(
                IndependentPaperPortfolio.tightenStop(bd("99"),bd("97"))));
        assertEquals(0,bd("102").compareTo(
                IndependentPaperPortfolio.tightenStop(bd("99"),bd("102"))));
    }
    @Test void B01_preclosedNarrowDryingBoxIsDetected() {
        var s=breakOnce(1);
        assertNotNull(s.frozenBoxTop(),s.reason());
        assertNotNull(s.frozenBoxBase());
        assertFalse(s.actionable());
    }
    @Test void B02_wickAbovePriorResistanceIsNotABreakout() {
        var s=breakOnce(2);
        assertFalse(s.actionable());
        assertTrue(s.rejectedFilters().contains("WICK_ONLY_BREAKOUT"));
    }
    @Test void B03_closeAboveBoxWithoutExtraVolumeIsBlocked() {
        var s=breakOnce(3);
        assertFalse(s.actionable());
        assertTrue(s.rejectedFilters().contains("BREAKOUT_VOLUME_NOT_ABOVE_HISTORY"));
    }
    @Test void B04_closedHighVolumeBreakoutCanProposeNextOpenEntry() {
        var s=breakOnce(4);
        assertTrue(s.actionable(),s.rejectedFilters().toString());
        assertTrue(s.referencePrice().compareTo(s.frozenBoxTop())>0);
    }
    @Test void B05_validBreakoutAllowsIndependentlyConfirmedRetest() {
        var detector=new RangeBreakoutDetector(params(),
                RangeBreakoutDetector.Variant.RETEST);
        var tape=box();
        tape.add(breakout(4));
        assertEquals(IndependentSetupSignal.Status.ALERT,
                detector.onClosed(tape,TICK).status());
        tape.add(bar(69,100.8,101.2,100.3,101.0,1200,800));
        var s=detector.onClosed(tape,TICK);
        assertTrue(s.actionable(),s.reason());
    }
    @Test void B06_retestStopIsBelowRetestLowNotBreakoutLow() {
        var detector=new RangeBreakoutDetector(params(),
                RangeBreakoutDetector.Variant.RETEST);
        var tape=box();
        tape.add(breakout(4));
        detector.onClosed(tape,TICK);
        tape.add(bar(69,100.8,101.2,100.3,101.0,1200,800));
        var s=detector.onClosed(tape,TICK);
        assertTrue(s.actionable(),s.reason());
        assertEquals(0,s.structuralStop().compareTo(bd("100.29")));
    }
    @Test void B07_closeBelowFrozenBaseInvalidatesRetest() {
        var detector=new RangeBreakoutDetector(params(),
                RangeBreakoutDetector.Variant.RETEST);
        var tape=box();
        tape.add(breakout(4));
        var armed=detector.onClosed(tape,TICK);
        tape.add(bar(69,100.7,100.9,99.2,99.5,1000,400));
        var s=detector.onClosed(tape,TICK);
        assertEquals(IndependentSetupSignal.Status.INVALIDATED,s.status());
        assertEquals(armed.frozenBoxBase(),s.frozenBoxBase());
    }
    @Test void E01_rejectsUnclosedBarsRatherThanUsingTheirVolume() {
        var tape=box();
        var c=breakout(4);
        tape.add(new MarketCandle(c.symbol(),c.interval(),c.openTime(),c.closeTime(),
                c.open(),c.high(),c.low(),c.close(),c.baseVolume(),
                c.quoteVolume(),c.numberOfTrades(),c.takerBuyBaseVolume(),
                c.takerBuyQuoteVolume(),false));
        assertThrows(IllegalArgumentException.class,()->
                new RangeBreakoutDetector(params(),
                        RangeBreakoutDetector.Variant.IMMEDIATE).onClosed(tape,TICK));
    }
    @Test void E02_breakoutCandleHighCannotRewriteFrozenBoxResistance() {
        var s=breakOnce(4);
        assertTrue(s.actionable());
        assertTrue(s.frozenBoxTop().compareTo(bd("101.5"))<0);
    }
    @Test void E03_combinedModeIsExplicitlyDisabledUntilArbitrationApproved() {
        var controller=new IndependentResearchController(null);
        assertEquals(false,controller.status().get("combinedStrategyEnabled"));
        assertEquals(false,controller.status().get("ordersEnabled"));
    }
    private static IndependentResearchRequest research(BigDecimal fee,int leverage,
            BigDecimal maintenance) {
        return new IndependentResearchRequest("BTCUSDT",100,100,"15m",
                BASE+100*CandleInterval.M5.millis()-1,params(),
                bd("1000"),bd("1"),bd("3"),leverage,fee,
                bd("0"),bd("0"),bd("0"),maintenance,
                TICK,bd("0.001"),bd("0.001"),bd("5"),bd("50"),100);
    }
    private static IndependentSetupSignal entry(BigDecimal stop) {
        return new IndependentSetupSignal(
                IndependentSetupSignal.Setup.TREND_PULLBACK,
                IndependentSetupSignal.Status.CONFIRMED,
                BASE+80*CandleInterval.M5.millis()-1,bd("100"),stop,
                null,null,"TEST_CONFIRMED",List.of("TEST"),List.of());
    }
    @Test void E04_stopUnderConservativeLiquidationFloorIsBlocked() {
        var paper=new IndependentPaperPortfolio("TREND_PULLBACK",
                research(bd("0"),10,bd("0.5")));
        paper.close(bar(80,99.8,100.1,99.6,100,1000,600),
                entry(bd("90")),trend());
        paper.begin(bar(81,100,100.5,99.5,100.1,1000,600));
        var s=paper.summary(bar(81,100,100.5,99.5,100.1,1000,600));
        assertEquals(1,s.blockedEntries());
        assertFalse(s.openPosition());
    }
    @Test void E05_feesCanEliminateGrossEdgeInPaperValidation() {
        var low=new IndependentPaperPortfolio("TREND_PULLBACK",
                research(bd("0"),1,bd("0")));
        var high=new IndependentPaperPortfolio("TREND_PULLBACK",
                research(bd("5"),1,bd("0")));
        var c=bar(80,99.8,100.1,99.6,100,1000,600);
        var next=bar(81,100,100.5,99.5,100.1,1000,600);
        low.close(c,entry(bd("99")),trend());
        high.close(c,entry(bd("99")),trend());
        low.begin(next);
        high.begin(next);
        assertTrue(low.summary(next).openPosition());
        assertFalse(high.summary(next).openPosition());
        assertEquals(1,high.summary(next).blockedEntries());
    }
}
