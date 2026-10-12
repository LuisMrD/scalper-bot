package br.com.tradingbot.analysis.levels;

/** Valores empiricos da implementacao; nao sao criterios prescritos por Brooks. */
public record MarketLevelsParameters(int atrLookback, double atrZoneMultiplier,
        double minZoneTolerancePct, int maxZones) {
    public MarketLevelsParameters {
        if (atrLookback < 2 || atrLookback > 100
                || !Double.isFinite(atrZoneMultiplier) || atrZoneMultiplier <= 0 || atrZoneMultiplier > 5
                || !Double.isFinite(minZoneTolerancePct) || minZoneTolerancePct <= 0 || minZoneTolerancePct > 5
                || maxZones < 1 || maxZones > 100) {
            throw new IllegalArgumentException("Parametros de niveis invalidos");
        }
    }
}
