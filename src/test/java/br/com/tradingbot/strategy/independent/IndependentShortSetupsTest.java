package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndependentShortSetupsTest {
    private static final long BEGIN=1_800_000_000_000L;
    private static final BigDecimal TICK=bd("0.01");
    private static BigDecimal bd(String n){return new BigDecimal(n);}
    private static BigDecimal bd(double n){return BigDecimal.valueOf(n);}
    private static IndependentSetupParameters p(){
        return new IndependentSetupParameters(2,2,3,3,bd("2"),
                bd("0.9"),bd("0.9"),bd("0.6"),bd("2"),
                bd("1.1"),bd("1.8"),8,bd("3"),
                bd("0.9"),bd("0.9"),bd("1.2"),5,bd("0.5"),
                bd("1"),14,20,IndependentSetupParameters.DirectStopRule.BOX_BASE,bd("1"));
    }
    private static MarketCandle c(int i,double o,double h,double l,
            double close,double volume,double buyer){
        long t=BEGIN+i*CandleInterval.M5.millis();
        return new MarketCandle("BTCUSDT",CandleInterval.M5,t,
                t+CandleInterval.M5.millis()-1,bd(o),bd(h),bd(l),
                bd(close),bd(volume),bd(volume),100,bd(buyer),bd(buyer),true);
    }
    private static MarketCandle flip(MarketCandle orig) {
        BigDecimal base=bd("200");
        return new MarketCandle(orig.symbol(),orig.interval(),orig.openTime(),
                orig.closeTime(),base.subtract(orig.open()),base.subtract(orig.low()),
                base.subtract(orig.high()),base.subtract(orig.close()),
                orig.baseVolume(),orig.quoteVolume(),orig.numberOfTrades(),
                orig.baseVolume().subtract(orig.takerBuyBaseVolume()),
                orig.quoteVolume().subtract(orig.takerBuyQuoteVolume()),true);
    }
    private static List<MarketCandle> trend() {
        var tape=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++) {
            double v=80+i*0.32+1.7*Math.sin(2*Math.PI*(i%8)/8.0);
            tape.add(c(i,v-0.5,v+0.5,v-1,v,1000,600));
        }
        return tape;
    }
    private static List<MarketCandle> shortPullback(boolean mixed) {
        var t=trend();
        t.add(c(60,99.8,100,99.1,99.4,700,200));
        if(mixed)t.add(c(61,98.4,99,98.1,98.7,600,190));
        else t.add(c(61,99,99.1,98.2,98.6,600,190));
        t.add(c(62,98,98.2,97.3,97.6,500,150));
        t.add(c(63,97.7,99.7,97.5,99.5,1300,900));
        return t.stream().map(IndependentShortSetupsTest::flip).toList();
    }
    private static List<MarketCandle> shortBox(int kind) {
        var t=trend();
        for(int i=0;i<8;i++) {
            double center=100.3+0.015*i,range=0.75-0.06*i;
            t.add(c(60+i,center-0.1,center+range/2,
                    center-range/2,center+0.1,900-65*i,540-40*i));
        }
        switch(kind){
            case 1->t.add(c(68,100.5,101.1,100.3,100.6,1600,900)); // wick
            case 2->t.add(c(68,100.7,101.5,100.6,101.2,600,400)); // weak volume
            case 3->t.add(c(68,100.7,101.5,100.6,101.2,1800,1100)); // valid
            default->throw new IllegalArgumentException();
        }
        return t.stream().map(IndependentShortSetupsTest::flip).toList();
    }
    private static IndependentResearchRequest req() {
        return new IndependentResearchRequest("BTCUSDT",100,100,"5m","15m",
                BEGIN+100*CandleInterval.M5.millis()-1,p(),
                bd("1500"),bd("0.5"),bd("3"),1,bd("0.05"),bd("0.02"),
                bd("0"),bd("0"),bd("0.5"),TICK,bd("0.001"),
                bd("0.001"),bd("5"),bd("50"),30);
    }
    private static IndependentSetupSignal signal(long at,BigDecimal stop,
            IndependentSetupSignal.Setup setup){
        return new IndependentSetupSignal(setup,
                IndependentSetupSignal.Status.CONFIRMED,at,bd("100"),stop,
                null,null,"TEST_SHORT",List.of("TEST"),List.of());
    }

    @Test void shortStrictRequiresContractingBullishBounceAndBearishConfirmation() {
        var s=new ShortTrendPullbackDetector(p(),
                ShortTrendPullbackDetector.RetreatMode.ALL_BULLISH)
                .evaluate(shortPullback(false),TICK);
        assertEquals(IndependentSetupSignal.Side.SHORT,s.side());
        assertTrue(s.actionable(),s.rejectedFilters().toString());
        assertTrue(s.structuralStop().compareTo(s.referencePrice())>0);
    }
    @Test void shortMixedAllowsRedPauseButStrictRemainsBlocked() {
        var tape=shortPullback(true);
        var strict=new ShortTrendPullbackDetector(p(),
                ShortTrendPullbackDetector.RetreatMode.ALL_BULLISH).evaluate(tape,TICK);
        var mixed=new ShortTrendPullbackDetector(p(),
                ShortTrendPullbackDetector.RetreatMode.MIXED_NET_RISE).evaluate(tape,TICK);
        assertTrue(strict.rejectedFilters().contains("SHORT_PULLBACK_NOT_ORDERLY_BULLISH"));
        assertTrue(mixed.actionable(),mixed.rejectedFilters().toString());
        assertTrue(mixed.passedFilters().contains("SHORT_MIXED_PULLBACK_NET_RISE"));
    }
    @Test void shortWickCannotBreakFrozenBaseWithoutClose() {
        var s=new ShortRangeBreakoutDetector(p(),
                ShortRangeBreakoutDetector.Variant.IMMEDIATE).onClosed(shortBox(1),TICK);
        assertFalse(s.actionable());
        assertEquals("SHORT_WICK_ONLY_BREAKOUT",s.reason());
        assertNotNull(s.frozenBoxBase());
    }
    @Test void shortBreakoutNeedsVolumeEvenAfterBearishClose() {
        var s=new ShortRangeBreakoutDetector(p(),
                ShortRangeBreakoutDetector.Variant.IMMEDIATE).onClosed(shortBox(2),TICK);
        assertFalse(s.actionable());
        assertTrue(s.rejectedFilters().contains("SHORT_BREAKOUT_VOLUME_NOT_ABOVE_HISTORY"));
    }
    @Test void shortConfirmedBreakoutHasStopAboveMarket() {
        var s=new ShortRangeBreakoutDetector(p(),
                ShortRangeBreakoutDetector.Variant.IMMEDIATE).onClosed(shortBox(3),TICK);
        assertTrue(s.actionable(),s.rejectedFilters().toString());
        assertTrue(s.referencePrice().compareTo(s.frozenBoxBase())<0);
        assertTrue(s.structuralStop().compareTo(s.referencePrice())>0);
    }
    @Test void shortRetestWaitsUntilFormerFloorActsAsResistance() {
        var tape=new ArrayList<>(shortBox(3));
        var detector=new ShortRangeBreakoutDetector(p(),
                ShortRangeBreakoutDetector.Variant.RETEST);
        var armed=detector.onClosed(tape,TICK);
        assertEquals(IndependentSetupSignal.Status.ALERT,armed.status());
        // Mirrored from a bullish long retest; SHORT reacts after touching
        // underside of frozen former support.
        tape.add(flip(c(69,100.8,101.2,100.3,101,1200,800)));
        var confirmed=detector.onClosed(tape,TICK);
        assertTrue(confirmed.actionable(),confirmed.reason());
        assertTrue(confirmed.structuralStop().compareTo(confirmed.referencePrice())>0);
    }
    @Test void shortRetestInvalidatesOnlyOnCloseAboveFrozenTop() {
        var tape=new ArrayList<>(shortBox(3));
        var detector=new ShortRangeBreakoutDetector(p(),
                ShortRangeBreakoutDetector.Variant.RETEST);
        var armed=detector.onClosed(tape,TICK);
        tape.add(flip(c(69,100.7,100.9,99.2,99.5,1000,400)));
        var failed=detector.onClosed(tape,TICK);
        assertEquals(IndependentSetupSignal.Status.INVALIDATED,failed.status());
        assertEquals("SHORT_CLOSE_ABOVE_FROZEN_BOX_TOP",failed.reason());
        assertEquals(armed.frozenBoxTop(),failed.frozenBoxTop());
    }
    @Test void nextBarShortSellUsesBidAndHighCrossTriggersStopBuyAtAsk() {
        var paper=new IndependentPaperPortfolio("TREND_PULLBACK_SHORT",req());
        long entrySignal=BEGIN+80*CandleInterval.M5.millis()-1;
        paper.close(c(80,100,101,99,100,1000,400),
                signal(entrySignal,bd("102"),IndependentSetupSignal.Setup.TREND_PULLBACK_SHORT),
                trend());
        var opened=c(81,100,100.5,98.8,99.3,1000,400);
        paper.begin(opened);
        assertTrue(paper.summary(opened).openPosition());
        assertEquals(1,paper.summary(opened).acceptedSignals());
        // Price increases through the stop: short should LOSE, not profit.
        paper.begin(c(82,103,104,102.5,103.5,1000,500));
        var out=paper.summary(opened);
        assertEquals(1,out.closedTrades());
        assertTrue(out.trades().getFirst().netPnlUsdt().signum()<0);
        assertEquals("STOP",out.trades().getFirst().reason());
        assertTrue(out.trades().getFirst().exit().compareTo(
                out.trades().getFirst().entry())>0);
    }
    @Test void shortPaperRealizesProfitWhenBoughtBackBelowEntry() {
        var paper=new IndependentPaperPortfolio("RANGE_BREAKOUT_SHORT",req());
        var breakoutSignal=signal(BEGIN+80*CandleInterval.M5.millis()-1,
                bd("103"),IndependentSetupSignal.Setup.RANGE_BREAKOUT_SHORT);
        paper.close(c(80,100,101,99,100,1000,400),breakoutSignal,trend());
        var opened=c(81,100,100.2,97,97.5,1000,400);
        paper.begin(opened);
        assertTrue(paper.summary(opened).openPosition());
        // Direct account's hypothetical stop/timed close can be forced
        // through the configured max holding, without intrabar lookahead.
        for(int i=82;i<112;i++) {
            var bar=c(i,97.5,98,96,97,1000,400);
            paper.begin(bar);
            paper.close(bar,null,List.of(bar));
        }
        var later=c(112,95,96,94,95,1000,400);
        paper.begin(later);
        var out=paper.summary(later);
        assertEquals(1,out.closedTrades());
        assertTrue(out.trades().getFirst().netPnlUsdt().signum()>0);
    }
    @Test void shortStructuralTrailingTightensOnlyDownward() {
        assertEquals(0,bd("99").compareTo(
                IndependentPaperPortfolio.tightenShortStop(bd("99"),bd("101"))));
        assertEquals(0,bd("97").compareTo(
                IndependentPaperPortfolio.tightenShortStop(bd("99"),bd("97"))));
        assertEquals(IndependentSetupSignal.Side.LONG,
                signal(BEGIN,bd("98"),IndependentSetupSignal.Setup.TREND_PULLBACK).side());
    }
    @Test void weeklySlopeDirectionalFilterAllowsOnlyItsMatchingSide() {
        assertTrue(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.UP,
                IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.UP,
                IndependentSetupSignal.Side.SHORT));
        assertTrue(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.DOWN,
                IndependentSetupSignal.Side.SHORT));
        assertFalse(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.DOWN,
                IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.FLAT,
                IndependentSetupSignal.Side.LONG));
        assertFalse(WeeklyEmaDirection.allows(WeeklyEmaDirection.Direction.FLAT,
                IndependentSetupSignal.Side.SHORT));
    }

    @Test void crossingShortSignalIntoLongPortfolioFailsClosed() {
        var paper=new IndependentPaperPortfolio("TREND_PULLBACK",req());
        assertThrows(IllegalArgumentException.class,()->paper.close(
                c(80,100,101,99,100,1000,400),
                signal(BEGIN,bd("102"),IndependentSetupSignal.Setup.TREND_PULLBACK_SHORT),
                trend()));
    }
}
