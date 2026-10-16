package br.com.tradingbot.strategy.independent;

import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Binance UTC week: Monday 00:00:00.000 through Sunday 23:59:59.999.
 * NEVER use a partly formed current-week candle in a daily decision.
 */
final class CompletedWeeklyTrend {
    private CompletedWeeklyTrend() {}

    static void requireCompletedWeeklyCandle(MarketCandle week, long dailyCloseTime) {
        if (week == null || week.interval() != CandleInterval.W1 || !week.closed()
                || week.closeTime() > dailyCloseTime
                || !weekStartUtcMonday(week.openTime()))
            throw new IllegalArgumentException(
                    "Weekly trend must use a fully closed Binance UTC 1w candle");
    }

    static boolean weekStartUtcMonday(long epochMillis) {
        var opening = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC);
        return opening.getDayOfWeek() == DayOfWeek.MONDAY
                && opening.getHour() == 0 && opening.getMinute() == 0
                && opening.getSecond() == 0 && opening.getNano() == 0;
    }
}
