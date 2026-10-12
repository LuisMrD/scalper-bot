package br.com.tradingbot.analysis.volume;

/** Limites operacionais ajustaveis; nao sao parametros prescritos por Anna Coulling. */
public record VolumeParameters(int lookback, int recentCount, double highVolumeRatio,
        double lowVolumeRatio, double wideSpreadRatio, double narrowSpreadRatio,
        double strongTakerShare, double minBodyRatio, double extremeCloseRatio) {
    public VolumeParameters {
        if (lookback < 5 || lookback > 100 || recentCount < 1 || recentCount > 100
                || !finiteBetween(highVolumeRatio, 1.01, 20.0)
                || !finiteBetween(lowVolumeRatio, 0.01, 0.99)
                || !finiteBetween(wideSpreadRatio, 1.01, 20.0)
                || !finiteBetween(narrowSpreadRatio, 0.01, 0.99)
                || !finiteBetween(strongTakerShare, 0.51, 0.99)
                || !finiteBetween(minBodyRatio, 0.01, 1.0)
                || !finiteBetween(extremeCloseRatio, 0.51, 0.99)) {
            throw new IllegalArgumentException("Parametros de VPA invalidos");
        }
    }
    private static boolean finiteBetween(double v, double min, double max) {
        return Double.isFinite(v) && v >= min && v <= max;
    }
}
