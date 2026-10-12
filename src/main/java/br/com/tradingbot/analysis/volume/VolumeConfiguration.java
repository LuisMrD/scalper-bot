package br.com.tradingbot.analysis.volume;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "volume-analysis")
public record VolumeConfiguration(
        @Min(5) @Max(100) int lookback,
        @Min(1) @Max(100) int recentCount,
        @DecimalMin("1.01") @DecimalMax("20.0") double highVolumeRatio,
        @DecimalMin("0.01") @DecimalMax("0.99") double lowVolumeRatio,
        @DecimalMin("1.01") @DecimalMax("20.0") double wideSpreadRatio,
        @DecimalMin("0.01") @DecimalMax("0.99") double narrowSpreadRatio,
        @DecimalMin("0.51") @DecimalMax("0.99") double strongTakerShare,
        @DecimalMin("0.01") @DecimalMax("1.0") double minBodyRatio,
        @DecimalMin("0.51") @DecimalMax("0.99") double extremeCloseRatio) {

    public VolumeParameters toParameters() {
        return new VolumeParameters(lookback, recentCount, highVolumeRatio,
                lowVolumeRatio, wideSpreadRatio, narrowSpreadRatio,
                strongTakerShare, minBodyRatio, extremeCloseRatio);
    }
}
