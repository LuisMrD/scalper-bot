package br.com.tradingbot.system;

import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.config.OpenAiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {
    private final TradingProperties trading;
    private final OpenAiProperties openAi;
    public SystemStatusController(TradingProperties trading, OpenAiProperties openAi) {
        this.trading = trading;
        this.openAi = openAi;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return new SystemStatusResponse(
                "binance-scalping-bot",
                trading.execution().mode(),
                trading.symbols(),
                trading.risk().isConfigured(),
                false,
                openAi.ready(), // O Luna so fica ativo quando explicitamente habilitado e com chave.
                "Etapa 8: analise Luna opcional sob demanda; envio de ordens continua bloqueado."
        );
    }
}
