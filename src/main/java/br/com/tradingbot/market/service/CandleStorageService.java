package br.com.tradingbot.market.service;

import br.com.tradingbot.integration.binance.BinanceExchangeInfo.SymbolInfo;
import br.com.tradingbot.market.model.InstrumentMarket;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.repository.InstrumentRepository;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandleStorageService {
    private final JdbcTemplate jdbc;
    private final InstrumentRepository instruments;
    public CandleStorageService(JdbcTemplate jdbc, InstrumentRepository instruments) {
        this.jdbc = jdbc;
        this.instruments = instruments;
    }

    @Transactional
    public void saveInstrument(SymbolInfo instrument) {
        jdbc.update("""
            INSERT INTO instruments(symbol,market_type,base_asset,quote_asset)
            VALUES(?,?,?,?)
            ON DUPLICATE KEY UPDATE base_asset=VALUES(base_asset),quote_asset=VALUES(quote_asset)
            """, instrument.symbol(), instrument.market().name(), instrument.baseAsset(), instrument.quoteAsset());
    }

    @Transactional
    public int saveClosed(SymbolInfo instrument, java.util.List<MarketCandle> candles) {
        saveInstrument(instrument);
        Long instrumentId = instruments.findBySymbolAndMarketType(instrument.symbol(), InstrumentMarket.USD_M_PERPETUAL)
                .orElseThrow().getId();
        int saved = 0;
        for (MarketCandle candle: candles) {
            if (!candle.closed()) continue;
            if (!instrument.symbol().equals(candle.symbol()))
                throw new IllegalArgumentException("Symbol diferente do instrumento");
            jdbc.update("""
                INSERT INTO candles(instrument_id,timeframe,open_time_epoch_ms,close_time_epoch_ms,
                  open_price,high_price,low_price,close_price,base_volume,quote_volume,
                  taker_buy_base_volume,taker_buy_quote_volume,number_of_trades,closed)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)
                ON DUPLICATE KEY UPDATE
                  close_time_epoch_ms=VALUES(close_time_epoch_ms),
                  open_price=VALUES(open_price),high_price=VALUES(high_price),low_price=VALUES(low_price),
                  close_price=VALUES(close_price),base_volume=VALUES(base_volume),
                  quote_volume=VALUES(quote_volume),taker_buy_base_volume=VALUES(taker_buy_base_volume),
                  taker_buy_quote_volume=VALUES(taker_buy_quote_volume),
                  number_of_trades=VALUES(number_of_trades),closed=TRUE
                """, instrumentId, candle.interval().code(), candle.openTime(), candle.closeTime(),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.baseVolume(),
                candle.quoteVolume(), candle.takerBuyBaseVolume(), candle.takerBuyQuoteVolume(), candle.numberOfTrades());
            saved++;
        }
        return saved;
    }
}
