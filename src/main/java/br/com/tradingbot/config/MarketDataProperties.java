package br.com.tradingbot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "market-data")
public record MarketDataProperties(
        @NotEmpty List<@NotNull String> intervals,
        boolean websocketEnabled,
        boolean bootstrapEnabled,
        @Min(1) @Max(30) int bootstrapDays,
        @Min(1) @Max(1500) int pageLimit,
        @Min(1) @Max(30) int maxPagesPerRequest,
        @Min(1) @Max(1440) int reconcileLookbackMinutes,
        @NotNull String websocketStreamPath
) {
    public MarketDataProperties {
        intervals = intervals == null ? List.of() : List.copyOf(intervals);
        intervals.forEach(br.com.tradingbot.market.model.CandleInterval::parse);
        if (!websocketStreamPath.matches("/(stream|market/stream)"))
            throw new IllegalArgumentException("WebSocket path invalido");
    }
}
