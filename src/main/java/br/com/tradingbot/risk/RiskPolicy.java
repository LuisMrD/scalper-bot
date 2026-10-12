package br.com.tradingbot.risk;

import java.math.BigDecimal;
import java.util.Objects;

/** Valores financeiros explicitos; nenhum percentual ou capital presumido. */
public record RiskPolicy(BigDecimal capitalUsdt, BigDecimal riskPerTradePct,
        BigDecimal dailyStopPct, int leverage, BigDecimal feePerSidePct,
        BigDecimal slippagePerSidePct, BigDecimal breakEvenTriggerR,
        BigDecimal structuralAtrBuffer) {
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
