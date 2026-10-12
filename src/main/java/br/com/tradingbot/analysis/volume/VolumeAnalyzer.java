package br.com.tradingbot.analysis.volume;

import br.com.tradingbot.analysis.volume.VolumeSnapshot.EffortResult;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.Observation;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.SpreadLevel;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.TakerBalance;
import br.com.tradingbot.analysis.volume.VolumeSnapshot.VolumeLevel;
import br.com.tradingbot.market.model.MarketCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** VPA descritiva. Calcula referencias SOMENTE com candles fechados ANTERIORES. */
public final class VolumeAnalyzer {
    private static final MathContext MC = MathContext.DECIMAL64;
    private final VolumeParameters p;

    public VolumeAnalyzer(VolumeParameters parameters) { this.p = Objects.requireNonNull(parameters); }

    /** Input cronologico ascendente, um unico par/timeframe, sem buracos. */
    public VolumeSnapshot analyze(List<MarketCandle> candles) {
        Objects.requireNonNull(candles, "candles");
        if (candles.size() < p.lookback() + 1)
            throw new IllegalArgumentException("Historico insuficiente: necessario ao menos " + (p.lookback() + 1) + " candles fechados");
        List<MarketCandle> data = List.copyOf(candles);
        MarketCandle first = Objects.requireNonNull(data.getFirst(), "candle");
        for (int i = 0; i < data.size(); i++) {
            MarketCandle c = Objects.requireNonNull(data.get(i), "candle");
            if (!c.closed() || !c.symbol().equals(first.symbol()) || c.interval() != first.interval())
                throw new IllegalArgumentException("Candles devem estar fechados e pertencer ao mesmo par/timeframe");
            if (i > 0 && c.openTime() - data.get(i - 1).openTime() != first.interval().millis())
                throw new IllegalArgumentException("Candles duplicados, fora de ordem ou com lacunas");
        }

        int start = Math.max(p.lookback(), data.size() - p.recentCount());
        var observations = new ArrayList<Observation>();
        for (int i = start; i < data.size(); i++) {
            BigDecimal precedingVolume = BigDecimal.ZERO;
            BigDecimal precedingSpread = BigDecimal.ZERO;
            for (int j = i - p.lookback(); j < i; j++) {
                precedingVolume = precedingVolume.add(data.get(j).quoteVolume());
                precedingSpread = precedingSpread.add(spread(data.get(j)));
            }
            BigDecimal meanVolume = precedingVolume.divide(BigDecimal.valueOf(p.lookback()), MC);
            BigDecimal meanSpread = precedingSpread.divide(BigDecimal.valueOf(p.lookback()), MC);
            observations.add(observe(data.get(i), meanVolume, meanSpread));
        }
        return new VolumeSnapshot(first.symbol(), first.interval().code(), data.getLast().closeTime(),
                data.size(), p.lookback(), observations);
    }

    private Observation observe(MarketCandle c, BigDecimal meanVolume, BigDecimal meanSpread) {
        BigDecimal span = spread(c);
        BigDecimal relVolume = ratio(c.quoteVolume(), meanVolume);
        BigDecimal relSpread = ratio(span, meanSpread);
        BigDecimal takerShare = ratio(c.takerBuyQuoteVolume(), c.quoteVolume());
        VolumeLevel volume = relVolume == null ? VolumeLevel.UNAVAILABLE :
                relVolume.compareTo(BigDecimal.valueOf(p.highVolumeRatio())) >= 0 ? VolumeLevel.HIGH :
                relVolume.compareTo(BigDecimal.valueOf(p.lowVolumeRatio())) <= 0 ? VolumeLevel.LOW : VolumeLevel.NORMAL;
        SpreadLevel spreadLevel = relSpread == null ? SpreadLevel.UNAVAILABLE :
                relSpread.compareTo(BigDecimal.valueOf(p.wideSpreadRatio())) >= 0 ? SpreadLevel.WIDE :
                relSpread.compareTo(BigDecimal.valueOf(p.narrowSpreadRatio())) <= 0 ? SpreadLevel.NARROW : SpreadLevel.NORMAL;
        TakerBalance takerBalance = takerShare == null ? TakerBalance.UNAVAILABLE :
                takerShare.compareTo(BigDecimal.valueOf(p.strongTakerShare())) >= 0 ? TakerBalance.BUY_DOMINANT :
                takerShare.compareTo(BigDecimal.ONE.subtract(BigDecimal.valueOf(p.strongTakerShare()))) <= 0 ?
                        TakerBalance.SELL_DOMINANT : TakerBalance.BALANCED;

        EffortResult effortResult = EffortResult.NONE;
        if (volume == VolumeLevel.HIGH) {
            BigDecimal body = c.close().subtract(c.open()).abs();
            BigDecimal bodyRatio = ratio(body, span);
            BigDecimal closePosition = span.signum() == 0 ? null : ratio(c.close().subtract(c.low()), span);
            BigDecimal lowerWick = c.open().min(c.close()).subtract(c.low());
            BigDecimal upperWick = c.high().subtract(c.open().max(c.close()));
            if (spreadLevel == SpreadLevel.NARROW || span.signum() == 0)
                effortResult = EffortResult.HIGH_EFFORT_SMALL_SPREAD;
            else if (bodyRatio != null && bodyRatio.compareTo(BigDecimal.valueOf(p.minBodyRatio())) >= 0
                    && spreadLevel == SpreadLevel.WIDE && closePosition != null) {
                if (c.close().compareTo(c.open()) > 0 && closePosition.compareTo(BigDecimal.valueOf(p.extremeCloseRatio())) >= 0)
                    effortResult = EffortResult.BULLISH_WIDE_SPREAD_HIGH_VOLUME;
                else if (c.close().compareTo(c.open()) < 0 && closePosition.compareTo(BigDecimal.ONE.subtract(BigDecimal.valueOf(p.extremeCloseRatio()))) <= 0)
                    effortResult = EffortResult.BEARISH_WIDE_SPREAD_HIGH_VOLUME;
            }
            if (effortResult == EffortResult.NONE && span.signum() > 0 && closePosition != null) {
                if (ratio(upperWick, span).compareTo(BigDecimal.valueOf(p.extremeCloseRatio())) >= 0)
                    effortResult = EffortResult.HIGH_VOLUME_UPPER_REJECTION;
                else if (ratio(lowerWick, span).compareTo(BigDecimal.valueOf(p.extremeCloseRatio())) >= 0)
                    effortResult = EffortResult.HIGH_VOLUME_LOWER_REJECTION;
            }
        }
        return new Observation(c.openTime(), c.closeTime(), c.baseVolume(), c.quoteVolume(),
                relVolume, relSpread, takerShare, volume, spreadLevel, takerBalance, effortResult);
    }

    private static BigDecimal spread(MarketCandle c) { return c.high().subtract(c.low()); }
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.divide(denominator, MC);
    }
}
