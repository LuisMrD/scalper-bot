-- Binance USD-M Futures: os instantes de candle sao epoch millis UTC.
-- Os ativos nao sao inventados/inseridos; serao importados do exchangeInfo na etapa 2.
CREATE TABLE instruments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    market_type VARCHAR(32) NOT NULL,
    base_asset VARCHAR(20) NULL,
    quote_asset VARCHAR(20) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_instrument_symbol_market UNIQUE (symbol, market_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE candles (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instrument_id BIGINT NOT NULL,
    timeframe VARCHAR(12) NOT NULL,
    open_time_epoch_ms BIGINT NOT NULL,
    close_time_epoch_ms BIGINT NOT NULL,
    open_price DECIMAL(30,12) NOT NULL,
    high_price DECIMAL(30,12) NOT NULL,
    low_price DECIMAL(30,12) NOT NULL,
    close_price DECIMAL(30,12) NOT NULL,
    base_volume DECIMAL(36,12) NOT NULL,
    quote_volume DECIMAL(36,12) NULL,
    taker_buy_base_volume DECIMAL(36,12) NULL,
    closed BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_candle_instrument FOREIGN KEY (instrument_id) REFERENCES instruments(id),
    CONSTRAINT uk_candle UNIQUE (instrument_id, timeframe, open_time_epoch_ms),
    CONSTRAINT ck_candle_time CHECK (close_time_epoch_ms >= open_time_epoch_ms),
    CONSTRAINT ck_candle_prices CHECK (low_price <= high_price),
    CONSTRAINT ck_candle_base_volume CHECK (base_volume >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A restricao uk_candle ja cria o indice composto usado para buscar candles.
