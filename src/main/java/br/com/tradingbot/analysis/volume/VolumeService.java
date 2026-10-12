package br.com.tradingbot.analysis.volume;

import br.com.tradingbot.market.service.MarketDataService;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VolumeService {
    private final MarketDataService marketData;
    private final VolumeAnalyzer analyzer;
    private final int lookback;

    public VolumeService(MarketDataService marketData, VolumeConfiguration config) {
        this.marketData = marketData;
        this.analyzer = new VolumeAnalyzer(config.toParameters());
        this.lookback = config.lookback();
    }

    public VolumeSnapshot analyze(String symbol, String interval, int limit) {
        if (limit < lookback + 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit deve ser ao menos " + (lookback + 1));
        var data = new ArrayList<>(marketData.recent(symbol, interval, limit));
        Collections.reverse(data); // DAO fornece candles mais recentes primeiro.
        try { return analyzer.analyze(data); }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex);
        }
    }
}
