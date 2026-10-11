package br.com.tradingbot.market.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Snapshot de uma barra de mercado. UTC epoch ms. Apenas barras fechadas sao persistidas. */
public record MarketCandle(
        String symbol, CandleInterval interval, long openTime, long closeTime,
        BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
        BigDecimal baseVolume, BigDecimal quoteVolume,
        long numberOfTrades, BigDecimal takerBuyBaseVolume,
        BigDecimal takerBuyQuoteVolume, boolean closed) {
    public MarketCandle {
        Objects.requireNonNull(symbol); Objects.requireNonNull(interval);
        Objects.requireNonNull(open); Objects.requireNonNull(high);
        Objects.requireNonNull(low); Objects.requireNonNull(close);
        Objects.requireNonNull(baseVolume); Objects.requireNonNull(quoteVolume);
        Objects.requireNonNull(takerBuyBaseVolume); Objects.requireNonNull(takerBuyQuoteVolume);
        if (!symbol.matches("[A-Z0-9]{5,32}")) throw new IllegalArgumentException("Symbol invalido");
        if (openTime < 0 || closeTime < openTime || closeTime - openTime != interval.millis()-1)
            throw new IllegalArgumentException("Janela temporal invalida");
        if (low.signum() <= 0 || low.compareTo(high) > 0 ||
            open.compareTo(low) < 0 || open.compareTo(high) > 0 ||
            close.compareTo(low) < 0 || close.compareTo(high) > 0)
            throw new IllegalArgumentException("OHLC invalido");
        if (baseVolume.signum() < 0 || quoteVolume.signum() < 0 ||
            takerBuyBaseVolume.signum() < 0 || takerBuyQuoteVolume.signum() < 0 ||
            takerBuyBaseVolume.compareTo(baseVolume)>0 ||
            takerBuyQuoteVolume.compareTo(quoteVolume)>0 || numberOfTrades<0)
            throw new IllegalArgumentException("Volume/trades invalidos");
    }
}
