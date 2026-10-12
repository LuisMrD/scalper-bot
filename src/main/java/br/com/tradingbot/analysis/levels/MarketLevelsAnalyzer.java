package br.com.tradingbot.analysis.levels;

import br.com.tradingbot.analysis.brooks.PriceActionAnalyzer;
import br.com.tradingbot.analysis.brooks.PriceActionParameters;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.ConfirmedPivot;
import br.com.tradingbot.analysis.brooks.PriceActionSnapshot.PivotType;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.FibonacciLevel;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.FibonacciSwing;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.PivotOrigin;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.PriceZone;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.SwingDirection;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot.ZonePosition;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Extrai zonas candidatas e Fibonacci de pivots JA CONFIRMADOS pelo analisador Brooks.
 * Os niveis sao descritivos e nao sao precos de ordem nem sinais de entrada.
 */
public final class MarketLevelsAnalyzer {
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final List<BigDecimal> RETRACEMENTS = List.of(
            new BigDecimal("0.236"), new BigDecimal("0.382"), new BigDecimal("0.500"),
            new BigDecimal("0.618"), new BigDecimal("0.786"));
    private static final List<BigDecimal> EXTENSIONS = List.of(
            new BigDecimal("1.272"), new BigDecimal("1.618"));

    private final MarketLevelsParameters parameters;
    private final PriceActionAnalyzer priceAction;
    private final int minimumCandles;

    public MarketLevelsAnalyzer(MarketLevelsParameters parameters, PriceActionParameters priceActionParameters) {
        this.parameters = Objects.requireNonNull(parameters);
        Objects.requireNonNull(priceActionParameters);
        this.priceAction = new PriceActionAnalyzer(priceActionParameters);
        this.minimumCandles = Math.max(priceActionParameters.minCandles(), parameters.atrLookback() + 1);
    }

    /** Recebe candles fechados, cronologicos, sem lacunas, de um unico par/timeframe. */
    public MarketLevelsSnapshot analyze(List<MarketCandle> candles) {
        Objects.requireNonNull(candles, "candles");
        if (candles.size() < minimumCandles) {
            throw new IllegalArgumentException("Historico insuficiente: necessario ao menos "
                    + minimumCandles + " candles fechados");
        }
        // Valida integridade e reaproveita a confirmacao SEM LOOKAHEAD dos pivots.
        var priceActionSnapshot = priceAction.analyze(candles);
        List<MarketCandle> data = List.copyOf(candles);
        MarketCandle last = data.getLast();
        BigDecimal atr = averageTrueRange(data);
        BigDecimal percentageWidth = last.close().multiply(
                BigDecimal.valueOf(parameters.minZoneTolerancePct()), MC).divide(ONE_HUNDRED, MC);
        BigDecimal tolerance = atr.multiply(BigDecimal.valueOf(parameters.atrZoneMultiplier()), MC)
                .max(percentageWidth);
        List<ConfirmedPivot> pivots = priceActionSnapshot.confirmedPivots();
        List<PriceZone> zones = buildZones(pivots, tolerance, last.close());
        FibonacciSwing swing = latestSwing(pivots);
        return new MarketLevelsSnapshot(last.symbol(), last.interval().code(), last.closeTime(),
                data.size(), last.close(), atr, tolerance, zones, swing);
    }

    /** Media simples do True Range; exige um candle anterior para cada TR. */
    private BigDecimal averageTrueRange(List<MarketCandle> candles) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = candles.size() - parameters.atrLookback(); i < candles.size(); i++) {
            MarketCandle current = candles.get(i);
            BigDecimal prevClose = candles.get(i - 1).close();
            BigDecimal trueRange = current.high().subtract(current.low())
                    .max(current.high().subtract(prevClose).abs())
                    .max(current.low().subtract(prevClose).abs());
            total = total.add(trueRange);
        }
        return total.divide(BigDecimal.valueOf(parameters.atrLookback()), MC);
    }

    private List<PriceZone> buildZones(List<ConfirmedPivot> pivots, BigDecimal tolerance, BigDecimal currentClose) {
        List<PriceZone> all = new ArrayList<>();
        cluster(pivots, PivotType.LOW, tolerance, currentClose, all);
        cluster(pivots, PivotType.HIGH, tolerance, currentClose, all);
        // Niveis mais proximos primeiro; nao e ranking de confiabilidade.
        return all.stream().sorted(Comparator
                .comparing((PriceZone z) -> z.center().subtract(currentClose).abs())
                .thenComparing(PriceZone::center)
                .thenComparing(PriceZone::origin))
                .limit(parameters.maxZones()).toList();
    }

    private static void cluster(List<ConfirmedPivot> pivots, PivotType type,
            BigDecimal tolerance, BigDecimal currentClose, List<PriceZone> destination) {
        var same = pivots.stream().filter(p -> p.type() == type)
                .sorted(Comparator.comparing(ConfirmedPivot::price)
                        .thenComparingLong(ConfirmedPivot::pivotOpenTime)).toList();
        List<ConfirmedPivot> group = new ArrayList<>();
        for (var pivot : same) {
            if (!group.isEmpty() && !fits(group, pivot.price(), tolerance)) {
                destination.add(zone(group, type, tolerance, currentClose));
                group.clear();
            }
            group.add(pivot);
        }
        if (!group.isEmpty()) destination.add(zone(group, type, tolerance, currentClose));
    }

    private static boolean fits(List<ConfirmedPivot> group, BigDecimal price, BigDecimal tolerance) {
        BigDecimal total = group.stream().map(ConfirmedPivot::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal center = total.divide(BigDecimal.valueOf(group.size()), MC);
        // Proibe encadeamentos que criem zonas cada vez mais largas.
        return price.subtract(center).abs().compareTo(tolerance) <= 0
                && price.subtract(group.getFirst().price()).compareTo(tolerance.multiply(TWO)) <= 0;
    }

    private static PriceZone zone(List<ConfirmedPivot> group, PivotType type,
            BigDecimal tolerance, BigDecimal currentClose) {
        BigDecimal sum = BigDecimal.ZERO;
        long firstConfirmed = Long.MAX_VALUE;
        long lastConfirmed = Long.MIN_VALUE;
        for (ConfirmedPivot pivot : group) {
            sum = sum.add(pivot.price());
            firstConfirmed = Math.min(firstConfirmed, pivot.confirmedAtCloseTime());
            lastConfirmed = Math.max(lastConfirmed, pivot.confirmedAtCloseTime());
        }
        BigDecimal center = sum.divide(BigDecimal.valueOf(group.size()), MC);
        BigDecimal halfWidth = tolerance.divide(TWO, MC);
        BigDecimal lower = group.getFirst().price().subtract(halfWidth).max(BigDecimal.ZERO);
        BigDecimal upper = group.getLast().price().add(halfWidth);
        ZonePosition position = currentClose.compareTo(lower) < 0 ? ZonePosition.ABOVE :
                currentClose.compareTo(upper) > 0 ? ZonePosition.BELOW : ZonePosition.OVERLAPPING;
        return new PriceZone(type == PivotType.LOW ? PivotOrigin.PIVOT_LOW : PivotOrigin.PIVOT_HIGH,
                position, center, lower, upper, group.size(), firstConfirmed, lastConfirmed);
    }

    private static FibonacciSwing latestSwing(List<ConfirmedPivot> pivots) {
        // Usa o ultimo par de pivots de tipos OPOSTOS, cada um confirmado no asOf.
        if (pivots.size() < 2) return null;
        for (int end = pivots.size() - 1; end > 0; end--) {
            ConfirmedPivot finish = pivots.get(end);
            for (int start = end - 1; start >= 0; start--) {
                ConfirmedPivot begin = pivots.get(start);
                if (begin.pivotOpenTime() >= finish.pivotOpenTime() || begin.type() == finish.type())
                    continue;
                if (begin.price().compareTo(finish.price()) == 0) continue;
                BigDecimal delta = finish.price().subtract(begin.price());
                SwingDirection direction = delta.signum() > 0 ? SwingDirection.UP : SwingDirection.DOWN;
                var retracements = RETRACEMENTS.stream()
                        .map(ratio -> new FibonacciLevel(ratio,
                                finish.price().subtract(delta.multiply(ratio, MC), MC))).toList();
                var extensions = EXTENSIONS.stream()
                        .map(ratio -> new FibonacciLevel(ratio,
                                begin.price().add(delta.multiply(ratio, MC), MC))).toList();
                return new FibonacciSwing(direction, begin.pivotOpenTime(), finish.pivotOpenTime(),
                        finish.confirmedAtCloseTime(), begin.price(), finish.price(),
                        retracements, extensions);
            }
        }
        return null;
    }
}
