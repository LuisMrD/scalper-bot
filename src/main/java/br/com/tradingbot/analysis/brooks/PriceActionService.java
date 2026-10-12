package br.com.tradingbot.analysis.brooks;

import br.com.tradingbot.market.service.MarketDataService;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PriceActionService {
    private final MarketDataService marketData;
    private final PriceActionAnalyzer analyzer;

    public PriceActionService(MarketDataService marketData, PriceActionConfiguration config) {
        this.marketData = marketData;
        this.analyzer = new PriceActionAnalyzer(config.toParameters());
    }

    public PriceActionSnapshot analyze(String symbol, String interval, int limit) {
        var ascending = new ArrayList<>(marketData.recent(symbol, interval, limit));
        Collections.reverse(ascending); // O repositorio retorna do mais recente para o mais antigo.
        try {
            return analyzer.analyze(ascending);
        } catch (IllegalArgumentException ex) {
            // Falta de historico ou lacunas nao geram conclusao otimista.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }
}
