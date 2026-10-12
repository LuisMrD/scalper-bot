package br.com.tradingbot.analysis.levels;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "market-levels")
public record MarketLevelsConfiguration(
        @Min(2) @Max(100) int atrLookback,
        @DecimalMin("0.01") @DecimalMax("5.0") double atrZoneMultiplier,
        @DecimalMin("0.01") @DecimalMax("5.0") double minZoneTolerancePct,
        @Min(1) @Max(100) int maxZones) {
    public MarketLevelsParameters toParameters() {
        return new MarketLevelsParameters(atrLookback, atrZoneMultiplier,
                minZoneTolerancePct, maxZones);
    }
}
