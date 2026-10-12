package br.com.tradingbot.analysis.context;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Timeframes diferentes compartilham UM cutoff; o ultimo fechamento pode variar por timeframe. */
public record MultiTimeframeMarketContext(String symbol, long requestedAsOfEpochMs,
        List<MarketContextSnapshot> timeframes) {
    public MultiTimeframeMarketContext {
        Objects.requireNonNull(symbol);
        timeframes = List.copyOf(timeframes);
        if (timeframes.isEmpty()) throw new IllegalArgumentException("Nenhum timeframe informado");
        var seen = new HashSet<String>();
        for (var frame : timeframes) {
            if (!symbol.equals(frame.symbol()) || frame.requestedAsOfEpochMs() != requestedAsOfEpochMs
                    || !seen.add(frame.interval())) {
                throw new IllegalArgumentException("Timeframes inconsistentes ou duplicados");
            }
        }
    }
}
