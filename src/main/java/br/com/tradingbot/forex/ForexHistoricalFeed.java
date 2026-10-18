package br.com.tradingbot.forex;

import java.util.List;

/** Adapters can later load from an FX broker, without coupling to Binance. */
public interface ForexHistoricalFeed {
    List<ForexBar> closed15m(String pair);
}
