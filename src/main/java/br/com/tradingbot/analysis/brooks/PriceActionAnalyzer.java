package br.com.tradingbot.analysis.brooks;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BarPattern;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Breakout;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.BreakoutState;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.ConfirmedPivot;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Direction;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.PivotType;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.Trend;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Extrai fatos OHLC de barras FECHADAS; nao decide entradas ou saidas. */
public final class PriceActionAnalyzer {
    private static final MathContext MATH = MathContext.DECIMAL64;
    private final PriceActionParameters p;

    public PriceActionAnalyzer(PriceActionParameters parameters) {
        this.p = Objects.requireNonNull(parameters);
    }

    /** Recebe candles em ordem cronologica ASCENDENTE, sem lacunas. */
    public PriceActionSnapshot analyze(List<MarketCandle> candles) {
        Objects.requireNonNull(candles, "candles");
        if (candles.size() < p.minCandles()) {
            throw new IllegalArgumentException("Historico insuficiente: necessario ao menos " + p.minCandles() + " candles fechados");
        }
        List<MarketCandle> data = List.copyOf(candles);
        var first = data.getFirst();
        for (int i = 0; i < data.size(); i++) {
            var c = data.get(i);
            if (!c.closed() || !c.symbol().equals(first.symbol()) || c.interval() != first.interval()) {
                throw new IllegalArgumentException("Todos os candles devem estar fechados, com mesmo par e timeframe");
            }
            if (i > 0 && c.openTime() - data.get(i - 1).openTime() != first.interval().millis()) {
                throw new IllegalArgumentException("Candles fora de ordem, duplicados ou com lacunas temporais");
            }
        }

        List<BarPattern> patterns = new ArrayList<>();
        int from = Math.max(0, data.size() - p.recentBarCount());
        for (int i = from; i < data.size(); i++) patterns.add(pattern(data, i));

        List<ConfirmedPivot> pivots = confirmedPivots(data);
        Trend trend = trend(pivots);
        Breakout breakout = breakout(data);
        MarketCandle last = data.getLast();
        boolean pullback = (trend == Trend.UP && last.close().compareTo(last.open()) < 0) ||
                (trend == Trend.DOWN && last.close().compareTo(last.open()) > 0);
        return new PriceActionSnapshot(first.symbol(), first.interval().code(), last.closeTime(),
                data.size(), trend, patterns, pivots, breakout, pullback);
    }

    private BarPattern pattern(List<MarketCandle> data, int i) {
        MarketCandle c = data.get(i);
        BigDecimal span = c.high().subtract(c.low());
        BigDecimal body = c.close().subtract(c.open()).abs();
        BigDecimal upperWick = c.high().subtract(c.open().max(c.close()));
        BigDecimal lowerWick = c.open().min(c.close()).subtract(c.low());
        Direction direction = c.close().compareTo(c.open()) > 0 ? Direction.BULL :
                c.close().compareTo(c.open()) < 0 ? Direction.BEAR : Direction.NEUTRAL;
        double bodyRatio = ratio(body, span);
        double upperRatio = ratio(upperWick, span);
        double lowerRatio = ratio(lowerWick, span);
        MarketCandle prev = i == 0 ? null : data.get(i - 1);
        boolean inside = prev != null && c.high().compareTo(prev.high()) <= 0 &&
                c.low().compareTo(prev.low()) >= 0;
        boolean outside = prev != null && c.high().compareTo(prev.high()) > 0 &&
                c.low().compareTo(prev.low()) < 0;
        boolean doji = span.signum() == 0 || bodyRatio <= p.dojiBodyMaxRatio();
        boolean trendBar = !doji && bodyRatio >= p.trendBodyMinRatio();
        boolean bullishRejection = lowerRatio >= p.rejectionWickMinRatio() &&
                c.close().compareTo(c.low().add(span.multiply(BigDecimal.valueOf(0.5)))) >= 0;
        boolean bearishRejection = upperRatio >= p.rejectionWickMinRatio() &&
                c.close().compareTo(c.low().add(span.multiply(BigDecimal.valueOf(0.5)))) <= 0;
        return new BarPattern(c.openTime(), direction, trendBar, doji, inside, outside,
                bullishRejection, bearishRejection);
    }

    private static double ratio(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? 0 : part.divide(whole, MATH).doubleValue();
    }

    private List<ConfirmedPivot> confirmedPivots(List<MarketCandle> data) {
        List<ConfirmedPivot> out = new ArrayList<>();
        for (int i = p.pivotLeft(); i < data.size() - p.pivotRight(); i++) {
            MarketCandle c = data.get(i);
            boolean high = true, low = true;
            for (int j = i - p.pivotLeft(); j <= i + p.pivotRight(); j++) {
                if (i == j) continue;
                // Extremos empatados nao sao pivots confirmados neste algoritmo.
                if (c.high().compareTo(data.get(j).high()) <= 0) high = false;
                if (c.low().compareTo(data.get(j).low()) >= 0) low = false;
            }
            long confirmedAt = data.get(i + p.pivotRight()).closeTime();
            if (high) out.add(new ConfirmedPivot(PivotType.HIGH, c.openTime(), confirmedAt, c.high()));
            if (low) out.add(new ConfirmedPivot(PivotType.LOW, c.openTime(), confirmedAt, c.low()));
        }
        out.sort(Comparator.comparingLong(ConfirmedPivot::pivotOpenTime)
                .thenComparing(ConfirmedPivot::type));
        return out;
    }

    private static Trend trend(List<ConfirmedPivot> pivots) {
        List<BigDecimal> highs = pivots.stream().filter(v -> v.type() == PivotType.HIGH)
                .map(ConfirmedPivot::price).toList();
        List<BigDecimal> lows = pivots.stream().filter(v -> v.type() == PivotType.LOW)
                .map(ConfirmedPivot::price).toList();
        if (highs.size() < 2 || lows.size() < 2) return Trend.UNDETERMINED;
        int dh = highs.getLast().compareTo(highs.get(highs.size() - 2));
        int dl = lows.getLast().compareTo(lows.get(lows.size() - 2));
        if (dh > 0 && dl > 0) return Trend.UP;
        if (dh < 0 && dl < 0) return Trend.DOWN;
        return Trend.RANGE;
    }

    private Breakout breakout(List<MarketCandle> data) {
        int last = data.size() - 1;
        Breakout previous = candidate(data, last - 1);
        MarketCandle c = data.get(last), prev = data.get(last - 1);
        if (previous.state() == BreakoutState.UP_CANDIDATE &&
                c.close().compareTo(prev.high()) > 0 && c.close().compareTo(c.open()) > 0) {
            return new Breakout(BreakoutState.UP_FOLLOW_THROUGH, previous.level(), prev.openTime());
        }
        if (previous.state() == BreakoutState.DOWN_CANDIDATE &&
                c.close().compareTo(prev.low()) < 0 && c.close().compareTo(c.open()) < 0) {
            return new Breakout(BreakoutState.DOWN_FOLLOW_THROUGH, previous.level(), prev.openTime());
        }
        return candidate(data, last);
    }

    private Breakout candidate(List<MarketCandle> data, int index) {
        if (index < p.breakoutLookback()) return Breakout.none();
        BigDecimal high = data.get(index - p.breakoutLookback()).high();
        BigDecimal low = data.get(index - p.breakoutLookback()).low();
        for (int i = index - p.breakoutLookback() + 1; i < index; i++) {
            high = high.max(data.get(i).high());
            low = low.min(data.get(i).low());
        }
        MarketCandle c = data.get(index);
        if (c.close().compareTo(high) > 0)
            return new Breakout(BreakoutState.UP_CANDIDATE, high, c.openTime());
        if (c.close().compareTo(low) < 0)
            return new Breakout(BreakoutState.DOWN_CANDIDATE, low, c.openTime());
        return Breakout.none();
    }
}
