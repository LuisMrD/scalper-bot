package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static br.com.tradingbot.strategy.independent.ClosedCandleFeatures.ema;

/**
 * Direction of completed daily EMA21 for an intraday entry. The currently
 * forming UTC daily bar can NEVER be consulted, even if marked closed.
 * Neutral EMA slope forbids both directions. No extra HTF price-action gate.
 */
final class DailyEmaDirection {
    private static final int PERIOD = 21;
    private DailyEmaDirection() {}

    static WeeklyEmaDirection.Direction fromClosedDays(
            List<MarketCandle> days, long primaryCloseTime) {
        Objects.requireNonNull(days, "Daily candles are required");
        if(days.size() < PERIOD+1)
            throw new IllegalArgumentException(
                    "At least 22 completed daily candles are required for EMA21 slope");
        MarketCandle last=null;
        for(var day:days) {
            if(day==null || day.interval()!=CandleInterval.D1 || !day.closed()
                    || day.closeTime()>primaryCloseTime
                    || day.openTime()%CandleInterval.D1.millis()!=0
                    || day.closeTime()!=day.openTime()+CandleInterval.D1.millis()-1)
                throw new IllegalArgumentException(
                        "Daily reference must contain only fully closed UTC 1d candles");
            if(last!=null && day.openTime()!=last.openTime()+CandleInterval.D1.millis())
                throw new IllegalArgumentException("Missing daily reference candle");
            last=day;
        }
        BigDecimal current=ema(days,days.size(),PERIOD);
        BigDecimal previous=ema(days,days.size()-1,PERIOD);
        int slope=current.compareTo(previous);
        return slope>0 ? WeeklyEmaDirection.Direction.UP
                :slope<0 ? WeeklyEmaDirection.Direction.DOWN
                :WeeklyEmaDirection.Direction.FLAT;
    }
}
