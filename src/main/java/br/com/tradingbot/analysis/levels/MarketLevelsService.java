package br.com.tradingbot.analysis.levels;

import br.com.tradingbot.analysis.brooks.PriceActionConfiguration;
import br.com.tradingbot.market.service.MarketDataService;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarketLevelsService {
    private final MarketDataService marketData;
    private final MarketLevelsAnalyzer analyzer;
    private final int requiredCandles;

    public MarketLevelsService(MarketDataService marketData, MarketLevelsConfiguration config,
            PriceActionConfiguration priceAction) {
        this.marketData = marketData;
        this.analyzer = new MarketLevelsAnalyzer(config.toParameters(), priceAction.toParameters());
        this.requiredCandles = Math.max(priceAction.minCandles(), config.atrLookback() + 1);
    }

    public MarketLevelsSnapshot analyze(String symbol, String interval, int limit) {
        if (limit < requiredCandles) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit deve ser ao menos " + requiredCandles);
        }
        var data = new ArrayList<>(marketData.recent(symbol, interval, limit));
        Collections.reverse(data); // DB entrega candles em ordem decrescente.
        try {
            return analyzer.analyze(data);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }
}
