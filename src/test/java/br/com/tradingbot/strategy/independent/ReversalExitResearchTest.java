package br.com.tradingbot.strategy.independent;

import static org.junit.jupiter.api.Assertions.*;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReversalExitResearchTest {
    private static final long EPOCH=1_800_000_000_000L;
    private static BigDecimal b(String s){return new BigDecimal(s);}
    private static MarketCandle candle(int idx,String open,String high,String low,
            String close,String volume,String buy) {
        long time=EPOCH+idx*CandleInterval.M15.millis();
        return new MarketCandle("BTCUSDT",CandleInterval.M15,
                time,time+CandleInterval.M15.millis()-1,
                b(open),b(high),b(low),b(close),
                b("1000"),b(volume),100,b("500"),b(buy),true);
    }
    private static MarketCandle flat(int index) {
        return candle(index,"100","100.5","99.5","100","100000","50000");
    }
    private static ArrayList<MarketCandle> warmup() {
        var out=new ArrayList<MarketCandle>();
        for(int i=0;i<60;i++)out.add(flat(i));
        return out;
    }
    private static IndependentSetupParameters params() {
        return new IndependentSetupParameters(2,2,3,5,b("0.5"),b("0.85"),
                b("0.85"),b("0.65"),b("2"),b("1.2"),b("2"),
                10,b("2"),b("0.85"),b("0.85"),b("1.2"),
                6,b("0.2"),b("1"),14,20,
                IndependentSetupParameters.DirectStopRule.BOX_BASE,b("1"));
    }
    private static IndependentResearchRequest request(){
        return new IndependentResearchRequest("BTCUSDT",2400,60,"15m","1d",
                EPOCH+3000*CandleInterval.M15.millis()-1,params(),
                b("1500"),b("0.5"),b("3"),1,
                b("0.05"),b("0.02"),b("0.01"),b("0"),b("0.5"),
                b("0.01"),b("0.001"),b("0.001"),b("5"),b("50"),30);
    }
    private static IndependentSetupSignal setup(boolean shortSide) {
        return new IndependentSetupSignal(shortSide
                ?IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED_SHORT
                :IndependentSetupSignal.Setup.TREND_PULLBACK_MIXED,
                IndependentSetupSignal.Status.CONFIRMED,
                flat(59).closeTime(),b("100"),b(shortSide?"104":"96"),
                null,null,"TEST",List.of(),List.of());
    }
    private static IndependentPaperPortfolio portfolio(
            boolean shortSide,ReversalExitPolicy policy) {
        String arm=shortSide?"TREND_PULLBACK_MIXED_SHORT":"TREND_PULLBACK_MIXED";
        return new IndependentPaperPortfolio(arm+"__"+policy.name(),
                request(),StopResearchPolicy.BASELINE,policy);
    }
    private static void enter(IndependentPaperPortfolio p,boolean shortSide) {
        p.close(flat(59),setup(shortSide),warmup());
        p.begin(flat(60));
        assertTrue(p.summary(flat(60)).openPosition());
    }
    private static MarketCandle bearish(int idx) {
        return candle(idx,"100","100.2","97.5","98","200000","20000");
    }
    private static MarketCandle bullish(int idx) {
        return candle(idx,"100","102.5","99.7","102","200000","180000");
    }
    @Test void longNeedsClosedBearishBreakAndAggressiveSellingVolume() {
        var bars=warmup();
        bars.add(flat(60));
        bars.add(bearish(61));
        assertTrue(ReversalExitDetector.confirmed(bars,params(),false));
        assertFalse(ReversalExitDetector.confirmed(bars,params(),true));
        bars.set(61,candle(61,"100","100.2","97.5","98","100000","50000"));
        assertFalse(ReversalExitDetector.confirmed(bars,params(),false),
                "price-only bearish candle must not exit");
        bars.set(61,candle(61,"100","100.2","99.0","99.2","200000","20000"));
        assertFalse(ReversalExitDetector.confirmed(bars,params(),false),
                "volume-only candle without strong bearish body must not exit");
    }
    @Test void shortNeedsClosedBullishBreakAndAggressiveBuyingVolume() {
        var bars=warmup();
        bars.add(flat(60));
        bars.add(bullish(61));
        assertTrue(ReversalExitDetector.confirmed(bars,params(),true));
        assertFalse(ReversalExitDetector.confirmed(bars,params(),false));
        bars.set(61,candle(61,"100","102.5","99.7","102","100000","50000"));
        assertFalse(ReversalExitDetector.confirmed(bars,params(),true));
    }
    @Test void longReversalExitsNextOpenOnlyAndControlStaysOpen() {
        var baseline=portfolio(false,ReversalExitPolicy.BASELINE);
        var alternate=portfolio(false,ReversalExitPolicy.PRICE_ACTION_VOLUME_REVERSAL);
        for(var p:List.of(baseline,alternate))enter(p,false);
        var history=warmup();
        history.add(flat(60));
        history.add(bearish(61));
        for(var p:List.of(baseline,alternate)) {
            p.begin(bearish(61));
            p.close(bearish(61),null,history);
            assertTrue(p.summary(bearish(61)).openPosition(),
                    "no exit at the same closed reversal candle");
        }
        var next=candle(62,"98","99","97.4","98.4","100000","50000");
        for(var p:List.of(baseline,alternate))p.begin(next);
        assertTrue(baseline.summary(next).openPosition());
        var outcome=alternate.summary(next);
        assertEquals(1,outcome.closedTrades());
        assertEquals("REVERSAL_PRICE_VOLUME",outcome.trades().getFirst().reason());
        assertTrue(outcome.trades().getFirst().netPnlUsdt().signum()<0);
    }
    @Test void shortReversalExitsNextOpenOnlyAndControlStaysOpen() {
        var baseline=portfolio(true,ReversalExitPolicy.BASELINE);
        var alternate=portfolio(true,ReversalExitPolicy.PRICE_ACTION_VOLUME_REVERSAL);
        for(var p:List.of(baseline,alternate))enter(p,true);
        var history=warmup();
        history.add(flat(60));
        history.add(bullish(61));
        for(var p:List.of(baseline,alternate)) {
            p.begin(bullish(61));
            p.close(bullish(61),null,history);
        }
        assertTrue(alternate.summary(bullish(61)).openPosition());
        var next=candle(62,"102","103","101","102","100000","50000");
        for(var p:List.of(baseline,alternate))p.begin(next);
        assertTrue(baseline.summary(next).openPosition());
        assertEquals("REVERSAL_PRICE_VOLUME",
                alternate.summary(next).trades().getFirst().reason());
    }
    @Test void protectiveStopHasPriorityWhenCandleHitsItBeforeReversalConfirmation() {
        var exit=portfolio(false,ReversalExitPolicy.PRICE_ACTION_VOLUME_REVERSAL);
        enter(exit,false);
        var hitsStop=candle(61,"100","100.2","95","96","200000","20000");
        exit.begin(hitsStop);
        var history=warmup();
        history.add(flat(60));
        history.add(hitsStop);
        exit.close(hitsStop,null,history);
        assertEquals("STOP",exit.summary(hitsStop).trades().getFirst().reason());
    }
    @Test void incompleteOrMissingCandleHistoryCannotCauseAnExit() {
        var history=warmup();
        history.add(flat(60));
        history.add(bearish(61));
        assertTrue(ReversalExitDetector.confirmed(history,params(),false));
        var prior=List.copyOf(history.subList(0,61));
        assertFalse(ReversalExitDetector.confirmed(prior,params(),false));
        var stale=candle(62,"100","100.5","99.5","100","100000","50000");
        history.add(stale);
        history.remove(61);
        assertThrows(IllegalArgumentException.class,
                ()->ReversalExitDetector.confirmed(history,params(),false));
    }
}
