package br.com.tradingbot.risk;

import br.com.tradingbot.decision.DecisionResult;
import java.math.BigDecimal;
import java.util.Objects;

/** Estado de carteira e filtros fornecidos explicitamente pelo chamador. */
public record RiskPreviewRequest(DecisionResult decision, BigDecimal equityUsdt,
        BigDecimal realizedDailyPnlUsdt, BigDecimal priceTick,
        BigDecimal quantityStep, BigDecimal minimumQuantity, BigDecimal minimumNotionalUsdt,
        int candleLimit, SimulatedPosition position) {
    public RiskPreviewRequest {
        Objects.requireNonNull(decision, "decision");
        RiskPolicy.requirePositive(equityUsdt, "equityUsdt");
        Objects.requireNonNull(realizedDailyPnlUsdt, "realizedDailyPnlUsdt obrigatorio");
        RiskPolicy.requirePositive(priceTick, "priceTick");
        RiskPolicy.requirePositive(quantityStep, "quantityStep");
        RiskPolicy.requirePositive(minimumQuantity, "minimumQuantity");
        RiskPolicy.requirePositive(minimumNotionalUsdt, "minimumNotionalUsdt");
        if (candleLimit < 21 || candleLimit > 500)
            throw new IllegalArgumentException("candleLimit deve estar entre 21 e 500");
        if (!decision.symbol().endsWith("USDT"))
            throw new IllegalArgumentException("Simulacao atual aceita apenas futuros cotados em USDT");
    }
}
