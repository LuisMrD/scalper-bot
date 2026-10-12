package br.com.tradingbot.risk;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/** Valores financeiros explicitos; nenhum percentual ou capital presumido. */
public record RiskPolicy(BigDecimal capitalUsdt, BigDecimal riskPerTradePct,
        BigDecimal dailyStopPct, int leverage, BigDecimal feePerSidePct,
        BigDecimal slippagePerSidePct, BigDecimal breakEvenTriggerR,
        BigDecimal structuralAtrBuffer, BigDecimal maxCostToInitialRiskPct) {
    public RiskPolicy {
        requirePositive(capitalUsdt, "capitalUsdt");
        requirePercent(riskPerTradePct, "riskPerTradePct", false);
        requirePercent(dailyStopPct, "dailyStopPct", false);
        if (leverage < 1 || leverage > 125) throw new IllegalArgumentException("leverage invalido");
        requirePercent(feePerSidePct, "feePerSidePct", true);
        requirePercent(slippagePerSidePct, "slippagePerSidePct", true);
        requirePositive(breakEvenTriggerR, "breakEvenTriggerR");
        if (breakEvenTriggerR.compareTo(BigDecimal.valueOf(20)) > 0)
            throw new IllegalArgumentException("breakEvenTriggerR excessivo");
        requirePositive(structuralAtrBuffer, "structuralAtrBuffer");
        if (structuralAtrBuffer.compareTo(BigDecimal.valueOf(10)) > 0)
            throw new IllegalArgumentException("structuralAtrBuffer excessivo");
        if (maxCostToInitialRiskPct != null) {
            requirePercent(maxCostToInitialRiskPct, "maxCostToInitialRiskPct", false);
        }
    }
    /** Construtor de compatibilidade para testes/politicas sem filtro adicional. */
    public RiskPolicy(BigDecimal capitalUsdt, BigDecimal riskPerTradePct,
            BigDecimal dailyStopPct, int leverage, BigDecimal feePerSidePct,
            BigDecimal slippagePerSidePct, BigDecimal breakEvenTriggerR,
            BigDecimal structuralAtrBuffer) {
        this(capitalUsdt, riskPerTradePct, dailyStopPct, leverage, feePerSidePct,
                slippagePerSidePct, breakEvenTriggerR, structuralAtrBuffer, null);
    }

    /**
     * Custos por unidade / risco BRUTO por unidade ate o stop inicial.
     * Fees e slippage hipoteticos em ambos os lados: nao incluem funding,
     * spread adicional, impacto de mercado ou possiveis gaps.
     *
     * Comparacao por multiplicacao para evitar arredondamentos no limiar.
     * null desabilita o filtro; igualdade ao limiar e permitida.
     */
    public boolean costToInitialRiskTooHigh(BigDecimal entry, BigDecimal initialStop) {
        if (maxCostToInitialRiskPct == null) return false;
        requirePositive(entry, "entry");
        requirePositive(initialStop, "initialStop");
        BigDecimal distance = entry.subtract(initialStop).abs();
        if (distance.signum() == 0) return true;
        BigDecimal costRate = feePerSidePct.add(slippagePerSidePct)
                .divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        BigDecimal unitRoundTripCost = entry.add(initialStop).multiply(costRate);
        return unitRoundTripCost.multiply(BigDecimal.valueOf(100))
                .compareTo(distance.multiply(maxCostToInitialRiskPct)) > 0;
    }

    static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " deve ser positivo");
    }
    static void requirePercent(BigDecimal value, String name, boolean allowZero) {
        Objects.requireNonNull(value, name + " obrigatorio");
        if ((allowZero ? value.signum() < 0 : value.signum() <= 0)
                || value.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException(name + " deve estar entre 0 e 100");
    }
}
