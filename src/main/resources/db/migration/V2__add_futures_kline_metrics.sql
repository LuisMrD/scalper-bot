-- Dados adicionais de volume/trades, compatibilidade com tabela V1.
ALTER TABLE candles
  ADD COLUMN number_of_trades BIGINT NULL,
  ADD COLUMN taker_buy_quote_volume DECIMAL(36,12) NULL;
