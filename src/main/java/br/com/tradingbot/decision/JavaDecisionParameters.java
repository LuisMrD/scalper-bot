package br.com.tradingbot.decision;

/** Parametros de prototipo, nao limites de risco ou recomendacoes de investimento. */
public record JavaDecisionParameters(
        int breakoutLookback, int retestMaxBars, int maxPrimaryStalenessBars,
        boolean requireHigherTimeframeAlignment,
        boolean requireDominantTakerVolume,
        boolean pullbackEnabled, boolean breakoutRetestEnabled) {
    public JavaDecisionParameters {
        if (breakoutLookback < 3 || breakoutLookback > 50
                || retestMaxBars < 1 || retestMaxBars > 10
                || maxPrimaryStalenessBars < 1 || maxPrimaryStalenessBars > 10)
            throw new IllegalArgumentException("Janelas da estrategia invalidas");
        if (!pullbackEnabled && !breakoutRetestEnabled)
            throw new IllegalArgumentException("Habilite ao menos um setup");
    }
}
