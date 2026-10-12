package br.com.tradingbot.analysis.context;

import br.com.tradingbot.analysis.brooks.PriceActionConfiguration;
import br.com.tradingbot.analysis.levels.MarketLevelsConfiguration;
import br.com.tradingbot.analysis.volume.VolumeConfiguration;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.service.MarketDataService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarketContextService {
    private static final int MAX_FRAMES = 5;
    private final MarketDataService marketData;
    private final MarketContextBuilder builder;
    private final Clock clock;

    @Autowired
    public MarketContextService(MarketDataService marketData, PriceActionConfiguration price,
            VolumeConfiguration volume, MarketLevelsConfiguration levels,
            MarketContextConfiguration config) {
        this(marketData, price, volume, levels, config, Clock.systemUTC());
    }

    MarketContextService(MarketDataService marketData, PriceActionConfiguration price,
            VolumeConfiguration volume, MarketLevelsConfiguration levels,
            MarketContextConfiguration config, Clock clock) {
        this.marketData = Objects.requireNonNull(marketData);
        this.clock = Objects.requireNonNull(clock);
        this.builder = new MarketContextBuilder(price.toParameters(), volume.toParameters(),
                levels.toParameters(), config.recentCandles());
    }

    public MarketContextSnapshot analyze(String symbol, String interval, int limit, Long asOf) {
        long cutoff = cutoff(asOf);
        return analyzeFrame(symbol, interval, limit, cutoff);
    }

    public MultiTimeframeMarketContext analyzeMultiple(String symbol, List<String> intervals,
            int limit, Long asOf) {
        if (intervals == null || intervals.isEmpty() || intervals.size() > MAX_FRAMES)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe entre 1 e 5 timeframes");
        var seen = new HashSet<String>();
        for (String frame : intervals) {
            if (frame == null || !seen.add(frame))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timeframes duplicados ou vazios");
            parseInterval(frame);
        }
        validateLimit(limit);
        long cutoff = cutoff(asOf); // UM mesmo instante para todos os timeframes.
        var frames = new ArrayList<MarketContextSnapshot>();
        for (String interval : intervals) frames.add(analyzeFrame(symbol, interval, limit, cutoff));
        return new MultiTimeframeMarketContext(symbol, cutoff, frames);
    }

    private MarketContextSnapshot analyzeFrame(String symbol, String interval, int limit, long cutoff) {
        validateLimit(limit);
        parseInterval(interval);
        // Consulta uma unica janela por timeframe. Ordenacao do banco: DESC -> ASC para analisadores.
        var data = new ArrayList<MarketCandle>(marketData.recentAtOrBefore(symbol, interval, limit, cutoff));
        Collections.reverse(data);
        try {
            return builder.build(data, cutoff);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Contexto indisponivel para " + symbol + "/" + interval + ": " + e.getMessage(), e);
        }
    }

    private void validateLimit(int limit) {
        if (limit < builder.minimumCandles() || limit > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit deve estar entre " + builder.minimumCandles() + " e 500");
    }

    private long cutoff(Long asOf) {
        long now = clock.millis();
        long cutoff = asOf == null ? now : asOf;
        if (cutoff < 0 || cutoff > now)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asOf deve ser timestamp UTC em ms, nao futuro");
        return cutoff;
    }

    private void parseInterval(String interval) {
        try { CandleInterval.parse(interval); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
