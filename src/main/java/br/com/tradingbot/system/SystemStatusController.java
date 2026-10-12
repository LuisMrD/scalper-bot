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
                false, // IA ainda nao integrada; permanece inativa.
                "Etapas 1-5: Market Data, Price Action, Volume, niveis e Fibonacci; sem ordens ou IA."
        );
    }
}
