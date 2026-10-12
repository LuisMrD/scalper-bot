package br.com.tradingbot.simulation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** All contract filters are explicit; they must come from the target Binance instrument. */
public record BacktestRequest(String symbol, List<String> intervals, int steps, int contextLimit,
        Long asOf, boolean includeLuna, BigDecimal priceTick, BigDecimal quantityStep,
        BigDecimal minimumQuantity, BigDecimal minimumNotionalUsdt,
        BigDecimal breakEvenTriggerROverride, BigDecimal maxCostToInitialRiskPctOverride,
        Integer maxHoldingCandles) {
    public BacktestRequest {
        Objects.requireNonNull(symbol, "symbol");
        if (!symbol.matches("[A-Z0-9]{5,32}") || !symbol.endsWith("USDT"))
            throw new IllegalArgumentException("Somente contratos futuros USDT configurados");
        intervals = List.copyOf(Objects.requireNonNull(intervals, "intervals"));
        if (intervals.isEmpty() || intervals.size() > 5 || new HashSet<>(intervals).size() != intervals.size()
                || intervals.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Informe 1 a 5 timeframes sem repeticoes");
        if (steps < 2 || steps > 3000 || contextLimit < 21 || contextLimit > 500)
            throw new IllegalArgumentException("steps deve ser 2..3000; contextLimit deve ser 21..500");
        if (includeLuna && steps > 10)
            throw new IllegalArgumentException("Backtest Luna limitado a 10 steps pagos por solicitacao");
        if (asOf != null && asOf < 0) throw new IllegalArgumentException("asOf invalido");
        if (breakEvenTriggerROverride != null
                && (breakEvenTriggerROverride.signum() <= 0
                    || breakEvenTriggerROverride.compareTo(BigDecimal.valueOf(20)) > 0))
            throw new IllegalArgumentException("breakEvenTriggerROverride deve ser > 0 e <= 20");
        // null: herda a configuracao do ambiente; 0: desabilita apenas este replay.
        if (maxCostToInitialRiskPctOverride != null
                && (maxCostToInitialRiskPctOverride.signum() < 0
                    || maxCostToInitialRiskPctOverride.compareTo(BigDecimal.valueOf(100)) > 0))
            throw new IllegalArgumentException("maxCostToInitialRiskPctOverride deve estar entre 0 e 100");
        // null = sem limite de tempo; uma decisao de saida no fechamento so
        // pode executar na proxima abertura, nunca no mesmo candle.
        if (maxHoldingCandles != null && (maxHoldingCandles < 1 || maxHoldingCandles > 3000))
            throw new IllegalArgumentException("maxHoldingCandles deve ser 1..3000");
        positive(priceTick, "priceTick");
        positive(quantityStep, "quantityStep");
        positive(minimumQuantity, "minimumQuantity");
        positive(minimumNotionalUsdt, "minimumNotionalUsdt");
    }
    /** Compatibilidade com chamadas Java anteriores; JSON pode omitir override (null). */
    public BacktestRequest(String symbol, List<String> intervals, int steps, int contextLimit,
            Long asOf, boolean includeLuna, BigDecimal priceTick, BigDecimal quantityStep,
            BigDecimal minimumQuantity, BigDecimal minimumNotionalUsdt) {
        this(symbol, intervals, steps, contextLimit, asOf, includeLuna,
                priceTick, quantityStep, minimumQuantity, minimumNotionalUsdt, null, null, null);
    }

    /** Versao preexistente com override apenas do gatilho de break-even. */
    public BacktestRequest(String symbol, List<String> intervals, int steps, int contextLimit,
            Long asOf, boolean includeLuna, BigDecimal priceTick, BigDecimal quantityStep,
            BigDecimal minimumQuantity, BigDecimal minimumNotionalUsdt,
            BigDecimal breakEvenTriggerROverride) {
        this(symbol, intervals, steps, contextLimit, asOf, includeLuna,
                priceTick, quantityStep, minimumQuantity, minimumNotionalUsdt,
                breakEvenTriggerROverride, null, null);
    }

    /** Compatibilidade com override duplo usado pelos replays anteriores. */
    public BacktestRequest(String symbol, List<String> intervals, int steps, int contextLimit,
            Long asOf, boolean includeLuna, BigDecimal priceTick, BigDecimal quantityStep,
            BigDecimal minimumQuantity, BigDecimal minimumNotionalUsdt,
            BigDecimal breakEvenTriggerROverride, BigDecimal maxCostToInitialRiskPctOverride) {
        this(symbol, intervals, steps, contextLimit, asOf, includeLuna,
                priceTick, quantityStep, minimumQuantity, minimumNotionalUsdt,
                breakEvenTriggerROverride, maxCostToInitialRiskPctOverride, null);
    }

    private static void positive(BigDecimal v, String name) {
        if (v == null || v.signum() <= 0) throw new IllegalArgumentException(name + " obrigatorio e positivo");
    }
}
