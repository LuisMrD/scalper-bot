package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.MC;

/**
 * Extra constraints layered ONLY on an otherwise confirmed daily-approved
 * mixed LONG/SHORT pullback. No special exits, no entry changes to control.
 * Retest tolerance is the explicit existing retestTolerancePct (0.2% in
 * the frozen BTC config), not an optimized fitted constant.
 */
final class MidasEntryFilter {
    record Assessment(boolean allowed,String reason,
            Long anchorOpenTime,BigDecimal value,Long touchedCloseTime) {}

    static Assessment assess(MidasEntryPolicy policy,boolean shortSide,
            List<MarketCandle> bars,List<AnchoredMidasCurve.Point> points,
            int currentIndex,IndependentSetupParameters params) {
        if(policy==MidasEntryPolicy.BASELINE)
            return new Assessment(true,"BASELINE_UNCHANGED",null,null,null);
        if(currentIndex<0 || currentIndex>=bars.size()
                || points.size()!=currentIndex+1)
            throw new IllegalArgumentException("Missing MIDAS curve state");
        var current=bars.get(currentIndex);
        var point=points.get(currentIndex);
        if(!point.available())
            return new Assessment(false,"MIDAS_NO_CONFIRMED_ANCHOR_OR_VOLUME",null,null,null);
        boolean correctSide=shortSide
                ?current.close().compareTo(point.value())<0
                :current.close().compareTo(point.value())>0;
        if(!correctSide)
            return new Assessment(false,"MIDAS_WRONG_SIDE_OF_CURVE",
                    point.anchorOpenTime(),point.value(),null);
        if(policy==MidasEntryPolicy.MIDAS_TREND)
            return new Assessment(true,"MIDAS_TREND_CONFIRMED",
                    point.anchorOpenTime(),point.value(),null);
        // The preceding completed 3..5 pullback bars must have been close
        // enough to the SAME confirmed anchor as the signal bar. We do not
        // treat crossing the curve at any arbitrary point in history as retest.
        BigDecimal tolerance=params.retestTolerancePct()
                .divide(BigDecimal.valueOf(100),MC);
        int first=Math.max(0,currentIndex-params.pullbackMaxBars());
        for(int j=currentIndex-1;j>=first;j--) {
            var previous=points.get(j);
            if(!previous.available() || previous.anchorOpenTime()!=point.anchorOpenTime())
                continue;
            MarketCandle candle=bars.get(j);
            // Must overlap the actual MIDAS zone calculated on that BAR,
            // not the current curve height or a future revised anchor.
            BigDecimal lo=previous.value().multiply(BigDecimal.ONE.subtract(tolerance),MC);
            BigDecimal hi=previous.value().multiply(BigDecimal.ONE.add(tolerance),MC);
            if(candle.low().compareTo(hi)<=0 && candle.high().compareTo(lo)>=0)
                return new Assessment(true,"MIDAS_RETEST_REJECTION_CONFIRMED",
                        point.anchorOpenTime(),point.value(),candle.closeTime());
        }
        return new Assessment(false,"MIDAS_NO_CONFIRMED_RETEST",
                point.anchorOpenTime(),point.value(),null);
    }

    private MidasEntryFilter() {}
}
