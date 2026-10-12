package br.com.tradingbot.risk;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Taxas nao possuem defaults: usuario informa valores compativeis com sua conta. */
@Validated
@ConfigurationProperties(prefix = "risk-management")
public record RiskManagementConfiguration(
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal feePerSidePct,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal slippagePerSidePct,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal breakEvenTriggerR,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal structuralAtrBuffer) {}
