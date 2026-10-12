package br.com.tradingbot.analysis.brooks;

/** Parametros HEURISTICOS da implementacao, nao valores prescritos por Al Brooks. */
public record PriceActionParameters(
        int minCandles,
        int pivotLeft,
        int pivotRight,
        int breakoutLookback,
        int recentBarCount,
        double trendBodyMinRatio,
        double dojiBodyMaxRatio,
        double rejectionWickMinRatio) {
    public PriceActionParameters {
        if (minCandles < 10 || minCandles > 500 || pivotLeft < 1 || pivotRight < 1 ||
                breakoutLookback < 2 || recentBarCount < 1 || recentBarCount > 100 ||
                minCandles <= pivotLeft + pivotRight || minCandles <= breakoutLookback ||
                !Double.isFinite(trendBodyMinRatio) || !Double.isFinite(dojiBodyMaxRatio) ||
                !Double.isFinite(rejectionWickMinRatio) ||
                trendBodyMinRatio <= 0 || trendBodyMinRatio > 1 ||
                dojiBodyMaxRatio < 0 || dojiBodyMaxRatio >= trendBodyMinRatio ||
                rejectionWickMinRatio <= 0 || rejectionWickMinRatio > 1) {
            throw new IllegalArgumentException("Parametros de Price Action invalidos");
        }
    }
}
