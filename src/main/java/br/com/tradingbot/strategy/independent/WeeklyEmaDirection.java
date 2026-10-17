package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.ema;

/**
 * The ONLY higher-timeframe entry gate for independent pullback research.
 * EMA21 slope of fully completed weekly candles; Brooks RANGE is not a veto.
 * No current/unfinished week, no future candle and no discretionary threshold.
 */
final class WeeklyEmaDirection {
    enum Direction { UP, DOWN, FLAT }
    private static final int PERIOD = 21;
    private WeeklyEmaDirection() {}

    static Direction fromClosedWeeks(List<MarketCandle> weeks,long dailyCloseTime) {
        Objects.requireNonNull(weeks,"Weekly candles are required");
        if(weeks.size()<PERIOD+1)
            throw new IllegalArgumentException(
                    "At least 22 completed weekly candles are required for EMA21 slope");
        MarketCandle last=null;
        for(var week:weeks) {
            CompletedWeeklyTrend.requireCompletedWeeklyCandle(week,dailyCloseTime);
            if(last!=null && week.openTime()!=last.openTime()+CandleInterval.W1.millis())
                throw new IllegalArgumentException("Noncontiguous completed weekly history");
            last=week;
        }
        BigDecimal now=ema(weeks,weeks.size(),PERIOD);
        BigDecimal previous=ema(weeks,weeks.size()-1,PERIOD);
        int comparison=now.compareTo(previous);
        return comparison>0?Direction.UP:comparison<0?Direction.DOWN:Direction.FLAT;
    }

    static boolean allows(Direction weekly,IndependentSetupSignal.Side side) {
        return side==IndependentSetupSignal.Side.LONG
                ?weekly==Direction.UP:weekly==Direction.DOWN;
    }
}
