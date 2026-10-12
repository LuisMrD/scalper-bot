package br.com.tradingbot.decision;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "java-decision")
public record JavaDecisionConfiguration(
        @Min(3) @Max(50) int breakoutLookback,
        @Min(1) @Max(10) int retestMaxBars,
        @Min(1) @Max(10) int maxPrimaryStalenessBars,
        boolean requireHigherTimeframeAlignment,
        boolean requireDominantTakerVolume,
        boolean pullbackEnabled,
        boolean breakoutRetestEnabled) {
    public JavaDecisionParameters toParameters() {
        return new JavaDecisionParameters(breakoutLookback, retestMaxBars, maxPrimaryStalenessBars,
                requireHigherTimeframeAlignment, requireDominantTakerVolume,
                pullbackEnabled, breakoutRetestEnabled);
    }
}
