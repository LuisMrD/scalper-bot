package br.com.tradingbot.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Defesa de inicializacao: etapa 2 nao permite ordens em nenhum perfil.
 * A integracao com Binance so podera liberar ordens em etapa posterior revisada.
 */
@Component
public final class ExecutionSafetyGuard {
    private static final Set<String> TESTNET_HOSTS = Set.of("demo-fapi.binance.com");

    private final TradingProperties trading;
    private final BinanceProperties binance;

    public ExecutionSafetyGuard(TradingProperties trading, BinanceProperties binance) {
        this.trading = trading;
        this.binance = binance;
    }

    @PostConstruct
    public void validate() {
        if (trading.execution().ordersEnabled()) {
            throw new IllegalStateException(
                    "ETAPA 2: envio de ordens bloqueado. Use TRADING_ORDERS_ENABLED=false.");
        }
        if (trading.execution().mode() == ExecutionMode.TESTNET) {
            var uri = binance.restBaseUrl();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !TESTNET_HOSTS.contains(uri.getHost())) {
                throw new IllegalStateException("Perfil TESTNET deve apontar para REST demo-fapi.binance.com.");
            }
            var ws = binance.websocketBaseUrl();
            if (!"wss".equalsIgnoreCase(ws.getScheme())
                    || !"demo-fstream.binance.com".equalsIgnoreCase(ws.getHost())) {
                throw new IllegalStateException("Perfil TESTNET deve apontar para WS demo-fstream.binance.com.");
            }
        }
    }
}
