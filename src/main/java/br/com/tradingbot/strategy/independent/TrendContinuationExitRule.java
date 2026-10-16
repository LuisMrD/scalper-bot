package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.*;

/**
 * A fixed, falsifiable research rule, evaluated only on COMPLETED 15m bars.
 *
 * Starting at held-bar 30, continue to at most 60 bars only while:
 * - the last fully closed 1d EMA21 slope still agrees with the position;
 * - 15m EMA9 is on the correct side of EMA21 and EMA21 is sloping with it;
 * - the 15m close has not broken the last CONFIRMED supporting swing
 *   low (LONG) or resistance swing high (SHORT).
 *
 * The original initial protective stop and structural trailing are always
 * applied separately. Every failed condition schedules a NEXT-bar-open exit.
 * Never uses intrabar ordering, unconfirmed pivots or future daily bars.
 */
final class TrendContinuationExitRule {
    private TrendContinuationExitRule() {}

    static String failedCondition(List<MarketCandle> closed,
            IndependentSetupParameters p,boolean shortSide,
            WeeklyEmaDirection.Direction daily) {
        if(daily != (shortSide?WeeklyEmaDirection.Direction.DOWN:
                WeeklyEmaDirection.Direction.UP))
            return "DYNAMIC_DAILY_TREND_LOST";
        if(closed==null || closed.size()<23)
            return "DYNAMIC_INSUFFICIENT_PRIMARY_CONTEXT";
        check(closed);
        BigDecimal ema9=ema(closed,closed.size(),9);
        BigDecimal ema21=ema(closed,closed.size(),21);
        BigDecimal previous21=ema(closed,closed.size()-1,21);
        BigDecimal price=closed.getLast().close();
        boolean emaAgrees=shortSide
                ?ema9.compareTo(ema21)<0 && ema21.compareTo(previous21)<0
                    && price.compareTo(ema21)<0
                :ema9.compareTo(ema21)>0 && ema21.compareTo(previous21)>0
                    && price.compareTo(ema21)>0;
        if(!emaAgrees)return "DYNAMIC_15M_EMA_TREND_LOST";
        var swings=pivots(closed,closed.size(),p.pivotLeft(),p.pivotRight());
        ClosedCandleFeatures.Pivot latest=null;
        for(var swing:swings)
            if(swing.high()==shortSide)latest=swing;
        if(latest==null)return "DYNAMIC_NO_CONFIRMED_STRUCTURE";
        if(shortSide?price.compareTo(latest.price())>=0
                :price.compareTo(latest.price())<=0)
            return "DYNAMIC_15M_STRUCTURE_BROKEN";
        return null; // trend and structure both support extending the trade
    }
}
