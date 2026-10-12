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
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Prepara um replay sem consultas por candle: cada timeframe e carregado uma vez.
     * Os candles posteriores a cada decisao permanecem no cache, mas NUNCA entram
     * na janela entregue aos analisadores (busca binaria pelo closeTime <= cutoff).
     */
    public PreparedReplay prepareReplay(String symbol, List<String> intervals, int limit,
            long firstDecisionCutoff, long lastDecisionCutoff) {
        if (intervals == null || intervals.isEmpty() || intervals.size() > MAX_FRAMES
                || new HashSet<>(intervals).size() != intervals.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe 1 a 5 timeframes distintos");
        validateLimit(limit);
        if (firstDecisionCutoff < 0 || lastDecisionCutoff < firstDecisionCutoff
                || lastDecisionCutoff > clock.millis())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periodo do replay invalido");
        Map<String, List<MarketCandle>> loaded = new LinkedHashMap<>();
        for (String frame : intervals) {
            parseInterval(frame);
            long millis = CandleInterval.parse(frame).millis();
            long maxBars = (lastDecisionCutoff - firstDecisionCutoff) / millis + 1L;
            long required = limit + maxBars + 2L;
            // Prevencao contra requests com timeframes incompatíveis, p.ex. 1d + 1m.
            if (required > 60_000L)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Periodo/timeframes exigem historico excessivo; divida o replay em partes");
            var data = new ArrayList<>(marketData.recentAtOrBefore(symbol, frame,
                    (int) required, lastDecisionCutoff));
            Collections.reverse(data);
            loaded.put(frame, List.copyOf(data));
        }
        return new PreparedReplay(symbol, firstDecisionCutoff, lastDecisionCutoff,
                limit, Map.copyOf(loaded), List.copyOf(intervals), builder);
    }

    public static final class PreparedReplay {
        private final String symbol;
        private final long first, last;
        private final int limit;
        private final Map<String, List<MarketCandle>> history;
        private final List<String> intervals;
        private final MarketContextBuilder builder;

        private PreparedReplay(String symbol, long first, long last, int limit,
                Map<String, List<MarketCandle>> history, List<String> intervals,
                MarketContextBuilder builder) {
            this.symbol = symbol;
            this.first = first;
            this.last = last;
            this.limit = limit;
            this.history = history;
            this.intervals = intervals;
            this.builder = builder;
        }

        /** Apenas candles fechados ate cutoff sao apresentados aos tres analisadores. */
        public MultiTimeframeMarketContext at(long cutoff) {
            if (cutoff < first || cutoff > last)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cutoff fora do replay");
            var snapshots = new ArrayList<MarketContextSnapshot>();
            for (String frame : intervals) {
                var candles = history.get(frame);
                int lo = 0, hi = candles.size();
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (candles.get(mid).closeTime() <= cutoff) lo = mid + 1;
                    else hi = mid;
                }
                if (lo < limit)
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Historico insuficiente para " + symbol + "/" + frame
                                    + " no instante " + cutoff + ": necessario " + limit);
                try {
                    snapshots.add(builder.build(candles.subList(lo - limit, lo), cutoff));
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Contexto historico indisponivel para " + symbol + "/" + frame
                                    + " em " + cutoff + ": " + ex.getMessage(), ex);
                }
            }
            return new MultiTimeframeMarketContext(symbol, cutoff, snapshots);
        }
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
