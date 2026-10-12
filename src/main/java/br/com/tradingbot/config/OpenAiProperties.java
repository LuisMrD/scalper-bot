package br.com.tradingbot.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Luna fica desligado por padrao: LUNA_ENABLED=false. A chave nunca e versionada. */
@Validated
@ConfigurationProperties(prefix = "integrations.openai")
public record OpenAiProperties(
        boolean enabled,
        @NotBlank String model,
        String apiKey,
        @Min(128) @Max(2048) int maxOutputTokens,
        @Min(2) @Max(60) int timeoutSeconds,
        @Min(1) @Max(10) int maxPrimaryStalenessBars) {

    public boolean ready() { return enabled && apiKey != null && !apiKey.isBlank(); }
}
