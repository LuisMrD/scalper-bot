package br.com.tradingbot.system;

import br.com.tradingbot.config.TradingProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {
    private final TradingProperties trading;
    public SystemStatusController(TradingProperties trading) {
        this.trading = trading;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return new SystemStatusResponse(
                "binance-scalping-bot",
                trading.execution().mode(),
                trading.symbols(),
                trading.risk().isConfigured(),
                false,
                false, // A etapa 1 nao faz chamadas de IA, mesmo que a configuracao esteja presente.
                "Etapa 2: Market Data REST/WS; sem sinais, GPT ou envio de ordens."
        );
    }
}
