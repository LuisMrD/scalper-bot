package br.com.tradingbot.analysis.brooks;

import java.math.BigDecimal;
import java.util.List;

/** Snapshot deterministico; nao representa recomendacao ou ordem de trading. */
public record PriceActionSnapshot(
        String symbol,
        String interval,
        long asOfCloseTime,
        int candlesAnalyzed,
        Trend trend,
        List<BarPattern> recentBars,
        List<ConfirmedPivot> confirmedPivots,
        Breakout breakout,
        boolean pullbackCandidate) {

    public PriceActionSnapshot {
        recentBars = List.copyOf(recentBars);
        confirmedPivots = List.copyOf(confirmedPivots);
    }

    public enum Trend { UP, DOWN, RANGE, UNDETERMINED }
    public enum Direction { BULL, BEAR, NEUTRAL }
    public enum PivotType { HIGH, LOW }
    public enum BreakoutState { NONE, UP_CANDIDATE, DOWN_CANDIDATE, UP_FOLLOW_THROUGH, DOWN_FOLLOW_THROUGH }

    public record BarPattern(long openTime, Direction direction, boolean trendBar,
            boolean doji, boolean insideBar, boolean outsideBar,
            boolean bullishRejection, boolean bearishRejection) {}

    /** O pivot so passa a existir a partir de confirmedAtCloseTime. */
    public record ConfirmedPivot(PivotType type, long pivotOpenTime,
            long confirmedAtCloseTime, BigDecimal price) {}

    /** level e null quando state=NONE. */
    public record Breakout(BreakoutState state, BigDecimal level,
            Long breakoutBarOpenTime) {
        public static Breakout none() { return new Breakout(BreakoutState.NONE, null, null); }
    }
}
