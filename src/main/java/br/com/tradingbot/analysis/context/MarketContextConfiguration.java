package br.com.tradingbot.analysis.context;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Parametros de apresentacao do contexto; nao sao sinais operacionais. */
@Validated
@ConfigurationProperties(prefix = "market-context")
public record MarketContextConfiguration(@Min(1) @Max(100) int recentCandles) {}
