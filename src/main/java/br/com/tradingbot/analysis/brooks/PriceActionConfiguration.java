package br.com.tradingbot.analysis.brooks;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Parametros do algoritmo sao ajustaveis e nao representam regras fixas do autor. */
@Validated
@ConfigurationProperties(prefix = "price-action")
public record PriceActionConfiguration(
        @Min(10) @Max(500) int minCandles,
        @Min(1) @Max(20) int pivotLeft,
        @Min(1) @Max(20) int pivotRight,
        @Min(2) @Max(100) int breakoutLookback,
        @Min(1) @Max(100) int recentBarCount,
        @DecimalMin("0.01") @DecimalMax("1.0") double trendBodyMinRatio,
        @DecimalMin("0.0") @DecimalMax("0.99") double dojiBodyMaxRatio,
        @DecimalMin("0.01") @DecimalMax("1.0") double rejectionWickMinRatio) {
    public PriceActionParameters toParameters() {
        return new PriceActionParameters(minCandles, pivotLeft, pivotRight, breakoutLookback,
                recentBarCount, trendBodyMinRatio, dojiBodyMaxRatio, rejectionWickMinRatio);
    }
}
