package br.com.tradingbot.market.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;

@Entity
@Table(name = "candles", uniqueConstraints = @UniqueConstraint(
        name = "uk_candle", columnNames = {"instrument_id", "timeframe", "open_time_epoch_ms"}))
public class CandleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private InstrumentEntity instrument;

    @Column(name = "timeframe", nullable = false, length = 12)
    private String timeframe;

    @Column(name = "open_time_epoch_ms", nullable = false)
    private long openTimeEpochMs;

    @Column(name = "close_time_epoch_ms", nullable = false)
    private long closeTimeEpochMs;

    @Column(name = "open_price", precision = 30, scale = 12, nullable = false)
    private BigDecimal openPrice;

    @Column(name = "high_price", precision = 30, scale = 12, nullable = false)
    private BigDecimal highPrice;

    @Column(name = "low_price", precision = 30, scale = 12, nullable = false)
    private BigDecimal lowPrice;

    @Column(name = "close_price", precision = 30, scale = 12, nullable = false)
    private BigDecimal closePrice;

    @Column(name = "base_volume", precision = 36, scale = 12, nullable = false)
    private BigDecimal baseVolume;

    @Column(name = "quote_volume", precision = 36, scale = 12)
    private BigDecimal quoteVolume;

    @Column(name = "taker_buy_base_volume", precision = 36, scale = 12)
    private BigDecimal takerBuyBaseVolume;

    @Column(name = "number_of_trades")
    private Long numberOfTrades;

    @Column(name = "taker_buy_quote_volume", precision = 36, scale = 12)
    private BigDecimal takerBuyQuoteVolume;

    @Column(name = "closed", nullable = false)
    private boolean closed;

    protected CandleEntity() {}

    // Etapa 2: construtor/fabrica e validacao de dados consumidos da Binance.
    public Long getId() { return id; }
    public InstrumentEntity getInstrument() { return instrument; }
    public String getTimeframe() { return timeframe; }
    public long getOpenTimeEpochMs() { return openTimeEpochMs; }
    public long getCloseTimeEpochMs() { return closeTimeEpochMs; }
    public BigDecimal getOpenPrice() { return openPrice; }
    public BigDecimal getHighPrice() { return highPrice; }
    public BigDecimal getLowPrice() { return lowPrice; }
    public BigDecimal getClosePrice() { return closePrice; }
    public BigDecimal getBaseVolume() { return baseVolume; }
    public BigDecimal getQuoteVolume() { return quoteVolume; }
    public BigDecimal getTakerBuyBaseVolume() { return takerBuyBaseVolume; }
    public Long getNumberOfTrades() { return numberOfTrades; }
    public BigDecimal getTakerBuyQuoteVolume() { return takerBuyQuoteVolume; }
    public boolean isClosed() { return closed; }
}
