package br.com.tradingbot.integration.binance;

import br.com.tradingbot.config.BinanceProperties;
import br.com.tradingbot.config.MarketDataProperties;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.market.service.MarketDataService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stream combinado de Klines. Apenas candles com x=true sao encaminhados para persistencia.
 * A reconciliacao REST periodica recupera janelas perdidas durante desconexoes.
 */
@Component
public class BinanceKlineWebSocket {
    private static final Logger log=LoggerFactory.getLogger(BinanceKlineWebSocket.class);
    private final BinanceProperties binance;
    private final TradingProperties trading;
    private final MarketDataProperties settings;
    private final BinanceKlineDecoder decoder;
    private final MarketDataService data;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final AtomicBoolean connecting=new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile boolean shuttingDown;

    public BinanceKlineWebSocket(BinanceProperties binance, TradingProperties trading,
            MarketDataProperties settings, BinanceKlineDecoder decoder,MarketDataService data) {
        this.binance=binance;this.trading=trading;this.settings=settings;this.decoder=decoder;this.data=data;
    }

    @Scheduled(initialDelayString="${market-data.scheduler.initial-delay-ms:10000}",
            fixedDelayString="${market-data.websocket-check-ms:30000}")
    public void reconnectIfNeeded() {
        if (!settings.websocketEnabled() || shuttingDown || socket!=null || !connecting.compareAndSet(false,true)) return;
        try {
            URI uri=streamUri();
            var listener=new KlineListener();
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri,listener)
                    .whenComplete((ws,err)->{
                        if(err!=null){
                            log.warn("Binance WS indisponivel: {}",err.toString());
                            socket=null;
                        } else if(shuttingDown || listener.closed.get()) {
                            ws.sendClose(WebSocket.NORMAL_CLOSURE,"shutdown");
                        } else {
                            socket=ws;
                            log.info("Binance WS conectado a {} streams",trading.symbols().size()*settings.intervals().size());
                        }
                        connecting.set(false);
                    });
        } catch (RuntimeException e) {
            connecting.set(false);
            log.warn("Falha ao preparar WS: {}",e.toString());
        }
    }
    URI streamUri() {
        String streams=trading.symbols().stream()
                .flatMap(symbol->settings.intervals().stream()
                        .map(interval->symbol.toLowerCase(Locale.ROOT)+"@kline_"+interval))
                .collect(java.util.stream.Collectors.joining("/"));
        // O prefixo varia entre endpoints WS legados e a nova API publica.
        return URI.create(binance.websocketBaseUrl().toString()+settings.websocketStreamPath()+"?streams="+streams);
    }
    @PreDestroy
    public void stop() {
        shuttingDown=true;
        WebSocket ws=socket;
        if(ws!=null) ws.sendClose(WebSocket.NORMAL_CLOSURE,"shutdown");
    }
    private final class KlineListener implements WebSocket.Listener {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final StringBuilder fragment=new StringBuilder();
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last) {
            fragment.append(text);
            if(last) {
                try {
                    var candle=decoder.websocket(fragment.toString());
                    if(candle!=null) data.ingestClosed(candle);
                } catch (RuntimeException e) {
                    log.warn("Kline WS descartado: {}", e.toString());
                } finally { fragment.setLength(0); }
            }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){
            closed.set(true);
            if(socket==ws) socket=null;
            log.warn("Binance WS desconectado: {}",status);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket ws,Throwable error) {
            closed.set(true);
            if(socket==ws) socket=null;
            log.warn("Binance WS falhou: {}",error.toString());
        }
    }
}
