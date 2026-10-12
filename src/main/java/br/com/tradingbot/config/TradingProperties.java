package br.com.tradingbot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        @NotEmpty List<@Pattern(regexp = "[A-Z0-9]{5,32}") String> symbols,
        @NotNull @Valid Execution execution,
        @NotNull @Valid Risk risk
) {
    public TradingProperties {
        if (symbols != null) {
            symbols = List.copyOf(symbols);
        }
    }

    public record Execution(@NotNull ExecutionMode mode, boolean ordersEnabled) {}

    // Sem valores financeiros padrao. A configuracao e obrigatoria antes de habilitar negociacao.
    public record Risk(
            @DecimalMin(value = "0.0", inclusive = false) BigDecimal capitalUsdt,
            @DecimalMin(value = "0.0", inclusive = false)
            @DecimalMax(value = "100.0") BigDecimal riskPerTradePct,
            @DecimalMin(value = "0.0", inclusive = false)
            @DecimalMax(value = "100.0") BigDecimal dailyStopPct,
            @Min(1) @Max(125) Integer leverage
    ) {
        public boolean isConfigured() {
            return capitalUsdt != null && riskPerTradePct != null
                    && dailyStopPct != null && leverage != null;
        }
    }
}
