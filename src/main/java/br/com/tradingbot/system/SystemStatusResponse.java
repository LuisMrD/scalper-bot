package br.com.tradingbot.system;

import br.com.tradingbot.config.ExecutionMode;
import java.util.List;

public record SystemStatusResponse(
        String application,
        ExecutionMode mode,
        List<String> configuredSymbols,
        boolean riskConfigured,
        boolean orderSubmissionAvailable,
        boolean openAiIntegrationActive,
        String message
) {}
