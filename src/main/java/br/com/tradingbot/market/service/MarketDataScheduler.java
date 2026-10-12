package br.com.tradingbot.market.service;

import br.com.tradingbot.config.MarketDataProperties;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketDataScheduler {
    private static final Logger log=LoggerFactory.getLogger(MarketDataScheduler.class);
    private final MarketDataService data;
    private final MarketDataProperties config;
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean bootstrapCompleted;
    public MarketDataScheduler(MarketDataService data, MarketDataProperties config){
        this.data=data; this.config=config;
    }
    @Scheduled(initialDelayString="${market-data.scheduler.initial-delay-ms:10000}",
               fixedDelayString="${market-data.scheduler.interval-ms:60000}")
    public void run() {
        if (!config.bootstrapEnabled() && !config.websocketEnabled()) return;
        if (!busy.compareAndSet(false,true)) return;
        try {
            if(config.bootstrapEnabled() && !bootstrapCompleted){
                data.bootstrap(); bootstrapCompleted=true;
            } else {
                data.reconcile();
            }
        } catch (Exception e) {
            log.warn("Falha de sincronizacao; nova tentativa no proximo ciclo: {}",e.toString());
        } finally { busy.set(false); }
    }
}
