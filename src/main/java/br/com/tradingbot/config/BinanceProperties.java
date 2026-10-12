package br.com.tradingbot.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "integrations.binance")
public record BinanceProperties(
        @NotNull URI restBaseUrl,
        @NotNull URI websocketBaseUrl
) {}
