package br.com.tradingbot.market.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "instruments", uniqueConstraints = @UniqueConstraint(
        name = "uk_instrument_symbol_market", columnNames = {"symbol", "market_type"}))
public class InstrumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "market_type", nullable = false, length = 32)
    private InstrumentMarket marketType;

    @Column(name = "base_asset", length = 20)
    private String baseAsset;

    @Column(name = "quote_asset", length = 20)
    private String quoteAsset;

    protected InstrumentEntity() {}

    public InstrumentEntity(String symbol, InstrumentMarket marketType,
                            String baseAsset, String quoteAsset) {
        this.symbol = java.util.Objects.requireNonNull(symbol);
        this.marketType = java.util.Objects.requireNonNull(marketType);
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public InstrumentMarket getMarketType() { return marketType; }
    public String getBaseAsset() { return baseAsset; }
    public String getQuoteAsset() { return quoteAsset; }
}
