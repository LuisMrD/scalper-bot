package br.com.tradingbot.analysis.levels;

import java.math.BigDecimal;
import java.util.List;

/** Niveis descritivos calculados somente com candles fechados e pivos confirmados. */
public record MarketLevelsSnapshot(
        String symbol, String interval, long asOfCloseTime, int candlesAnalyzed,
        BigDecimal lastClose, BigDecimal atr, BigDecimal zoneTolerance,
        List<PriceZone> zones, FibonacciSwing fibonacci) {
    public MarketLevelsSnapshot {
        zones = List.copyOf(zones);
    }

    /** Origem do nivel; um pivot LOW acima do preco nao e automaticamente suporte negociavel. */
    public enum PivotOrigin { PIVOT_LOW, PIVOT_HIGH }
    public enum ZonePosition { BELOW, ABOVE, OVERLAPPING }
    public enum SwingDirection { UP, DOWN }

    public record PriceZone(PivotOrigin origin, ZonePosition position,
            BigDecimal center, BigDecimal lowerBound, BigDecimal upperBound,
            int pivotCount, long firstConfirmedAtCloseTime, long lastConfirmedAtCloseTime) {}

    /** Null quando nao ha dois pivots opostos confirmados para definir um swing. */
    public record FibonacciSwing(SwingDirection direction, long startPivotOpenTime,
            long endPivotOpenTime, long endConfirmedAtCloseTime,
            BigDecimal startPrice, BigDecimal endPrice,
            List<FibonacciLevel> retracements, List<FibonacciLevel> extensions) {
        public FibonacciSwing {
            retracements = List.copyOf(retracements);
            extensions = List.copyOf(extensions);
        }
    }
    /** ratio e um decimal exato (ex.: 0.618 ou 1.618), nao percentual em pontos. */
    public record FibonacciLevel(BigDecimal ratio, BigDecimal price) {}
}
