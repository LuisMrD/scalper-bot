package br.com.tradingbot.analysis.context;

import br.com.tradingbot.analysis.brooks.PriceActionSnapshot;
import br.com.tradingbot.analysis.levels.MarketLevelsSnapshot;
import br.com.tradingbot.analysis.volume.VolumeSnapshot;
import br.com.tradingbot.market.model.MarketCandle;
import java.util.List;
import java.util.Objects;

/** Uma unica janela OHLCV gera tres leituras coerentes para ambos os motores de decisao. */
public record MarketContextSnapshot(
        String symbol, String interval, long requestedAsOfEpochMs,
        long asOfCloseTime, int candlesAnalyzed,
        List<MarketCandle> recentCandles,
        PriceActionSnapshot priceAction,
        VolumeSnapshot volume,
        MarketLevelsSnapshot marketLevels) {
    public MarketContextSnapshot {
        Objects.requireNonNull(symbol);
        Objects.requireNonNull(interval);
        Objects.requireNonNull(priceAction);
        Objects.requireNonNull(volume);
        Objects.requireNonNull(marketLevels);
        recentCandles = List.copyOf(recentCandles);
        if (requestedAsOfEpochMs < 0 || candlesAnalyzed < recentCandles.size() || recentCandles.isEmpty()
                || asOfCloseTime > requestedAsOfEpochMs
                || !symbol.equals(priceAction.symbol()) || !symbol.equals(volume.symbol())
                || !symbol.equals(marketLevels.symbol())
                || !interval.equals(priceAction.interval()) || !interval.equals(volume.interval())
                || !interval.equals(marketLevels.interval())
                || asOfCloseTime != priceAction.asOfCloseTime()
                || asOfCloseTime != volume.asOfCloseTime()
                || asOfCloseTime != marketLevels.asOfCloseTime()
                || recentCandles.getLast().closeTime() != asOfCloseTime) {
            throw new IllegalArgumentException("Contexto com leituras temporais ou instrumentos inconsistentes");
        }
    }
}
