package br.com.tradingbot.risk;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/** Taxas nao possuem defaults: usuario informa valores compativeis com sua conta. */
@Validated
@ConfigurationProperties(prefix = "risk-management")
public record RiskManagementConfiguration(
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal feePerSidePct,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal slippagePerSidePct,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal breakEvenTriggerR,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal structuralAtrBuffer,
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax("100.0") BigDecimal maxCostToInitialRiskPct) {
    // Required because this record also has a 4-argument test convenience constructor.
    // Spring Boot must select the canonical constructor for configuration binding.
    @ConstructorBinding
    public RiskManagementConfiguration {
    }

    public RiskManagementConfiguration(BigDecimal feePerSidePct,
            BigDecimal slippagePerSidePct, BigDecimal breakEvenTriggerR,
            BigDecimal structuralAtrBuffer) {
        this(feePerSidePct, slippagePerSidePct, breakEvenTriggerR,
                structuralAtrBuffer, null);
    }
}
