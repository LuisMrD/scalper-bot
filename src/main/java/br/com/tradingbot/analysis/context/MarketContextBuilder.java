package br.com.tradingbot.analysis.context;

import br.com.tradingbot.analysis.brooks.PriceActionAnalyzer;
import br.com.tradingbot.analysis.brooks.PriceActionParameters;
import br.com.tradingbot.analysis.levels.MarketLevelsAnalyzer;
import br.com.tradingbot.analysis.levels.MarketLevelsParameters;
import br.com.tradingbot.analysis.volume.VolumeAnalyzer;
import br.com.tradingbot.analysis.volume.VolumeParameters;
import br.com.tradingbot.market.model.MarketCandle;
import java.util.List;
import java.util.Objects;

/** Contexto deterministico: nao decide LONG/SHORT, nao negocia e nao chama LLM. */
public final class MarketContextBuilder {
    private final PriceActionAnalyzer priceAction;
    private final VolumeAnalyzer volume;
    private final MarketLevelsAnalyzer marketLevels;
    private final int minimumCandles;
    private final int recentCount;

    public MarketContextBuilder(PriceActionParameters price, VolumeParameters volume,
            MarketLevelsParameters levels, int recentCount) {
        Objects.requireNonNull(price);
        Objects.requireNonNull(volume);
        Objects.requireNonNull(levels);
        if (recentCount < 1 || recentCount > 100)
            throw new IllegalArgumentException("Quantidade de candles recentes invalida");
        this.priceAction = new PriceActionAnalyzer(price);
        this.volume = new VolumeAnalyzer(volume);
        this.marketLevels = new MarketLevelsAnalyzer(levels, price);
        this.minimumCandles = Math.max(price.minCandles(),
                Math.max(volume.lookback() + 1, levels.atrLookback() + 1));
        this.recentCount = recentCount;
    }

    public int minimumCandles() { return minimumCandles; }

    /** Candles fechados em ordem cronologica ascendente e cortados no tempo solicitado. */
    public MarketContextSnapshot build(List<MarketCandle> candles, long requestedAsOfEpochMs) {
        Objects.requireNonNull(candles, "candles");
        if (requestedAsOfEpochMs < 0) throw new IllegalArgumentException("asOf negativo");
        if (candles.size() < minimumCandles)
            throw new IllegalArgumentException("Historico insuficiente: ao menos "
                    + minimumCandles + " candles fechados e consecutivos");
        List<MarketCandle> data = List.copyOf(candles);
        for (MarketCandle candle : data) {
            if (candle == null || !candle.closed() || candle.closeTime() > requestedAsOfEpochMs)
                throw new IllegalArgumentException("Candle aberto ou posterior ao cutoff solicitado");
        }
        // Todos os analisadores recebem exatamente a MESMA lista de candles.
        // Cada analisador valida continuidade, par e timeframe; falha fechada em caso de lacunas.
        var pa = priceAction.analyze(data);
        var vpa = volume.analyze(data);
        var levels = marketLevels.analyze(data);
        var last = data.getLast();
        int start = Math.max(0, data.size() - recentCount);
        return new MarketContextSnapshot(last.symbol(), last.interval().code(), requestedAsOfEpochMs,
                last.closeTime(), data.size(), data.subList(start, data.size()), pa, vpa, levels);
    }
}
