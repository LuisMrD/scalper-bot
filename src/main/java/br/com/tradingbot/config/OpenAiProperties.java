package br.com.tradingbot.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "integrations.openai")
public record OpenAiProperties(boolean enabled, @NotBlank String model) {}
