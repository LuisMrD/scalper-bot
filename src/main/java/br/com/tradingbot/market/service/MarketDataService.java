package br.com.tradingbot.market.service;

import br.com.tradingbot.config.MarketDataProperties;
import br.com.tradingbot.config.TradingProperties;
import br.com.tradingbot.integration.binance.BinanceExchangeInfo;
import br.com.tradingbot.integration.binance.BinanceExchangeInfo.SymbolInfo;
import br.com.tradingbot.integration.binance.BinanceKlineDecoder;
import br.com.tradingbot.integration.binance.BinancePublicClient;
import br.com.tradingbot.market.model.CandleInterval;
import br.com.tradingbot.market.model.MarketCandle;
import br.com.tradingbot.market.repository.CandleRepository;
import br.com.tradingbot.market.repository.InstrumentRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private final TradingProperties trading;
    private final MarketDataProperties settings;
    private final BinancePublicClient client;
    private final BinanceExchangeInfo exchange;
    private final BinanceKlineDecoder decoder;
    private final CandleStorageService storage;
    private final InstrumentRepository instruments;
    private final CandleRepository candles;
    private final Clock clock;
    private final AtomicReference<Map<String,SymbolInfo>> validated = new AtomicReference<>(Map.of());

    @org.springframework.beans.factory.annotation.Autowired
    public MarketDataService(TradingProperties trading, MarketDataProperties settings,
            BinancePublicClient client, BinanceExchangeInfo exchange, BinanceKlineDecoder decoder,
            CandleStorageService storage, InstrumentRepository instruments, CandleRepository candles) {
        this(trading, settings, client, exchange, decoder, storage, instruments, candles, Clock.systemUTC());
    }
    MarketDataService(TradingProperties trading, MarketDataProperties settings,
            BinancePublicClient client, BinanceExchangeInfo exchange, BinanceKlineDecoder decoder,
            CandleStorageService storage, InstrumentRepository instruments, CandleRepository candles, Clock clock) {
        this.trading=trading; this.settings=settings; this.client=client; this.exchange=exchange;
        this.decoder=decoder; this.storage=storage; this.instruments=instruments; this.candles=candles;
        this.clock=clock;
    }
    public synchronized Map<String,SymbolInfo> refreshSymbols() {
        var loaded = exchange.decode(client.exchangeInfo(), Set.copyOf(trading.symbols()));
        loaded.values().forEach(storage::saveInstrument);
        validated.set(Map.copyOf(loaded));
        return validated.get();
    }
    private SymbolInfo checked(String symbol) {
        if (!trading.symbols().contains(symbol)) throw new IllegalArgumentException("Par nao configurado: " + symbol);
        var result=validated.get().get(symbol);
        if (result==null) result=refreshSymbols().get(symbol);
        return result;
    }
    public record SyncResult(String symbol, String interval, long from, long until,
                             int fetched, int persisted, int pages) {}

    /** Paginacao deterministica por horario inicial; reexecucoes sao idempotentes no MySQL. */
    public SyncResult sync(String symbol, String intervalCode, long from, long until) {
        var info = checked(symbol);
        var frame = CandleInterval.parse(intervalCode);
        long now = clock.millis();
        if (from < 0 || until <= from || from >= now)
            throw new IllegalArgumentException("Intervalo temporal invalido");
        if (until-from > Duration.ofDays(30).toMillis())
            throw new IllegalArgumentException("Maximo de 30 dias por solicitacao");
        long effectiveUntil = Math.min(until,now-1);
        long cursor=from; int fetched=0, persisted=0, pages=0;
        while (cursor<=effectiveUntil && pages<settings.maxPagesPerRequest()) {
            String response=client.candles(symbol,frame,cursor,effectiveUntil,settings.pageLimit());
            var decoded=decoder.rest(symbol,frame,response,now);
            if(decoded.isEmpty()) break;
            long pageCursor=cursor;
            if (decoded.stream().anyMatch(c->c.openTime()<pageCursor || c.openTime()>effectiveUntil))
                throw new IllegalStateException("Binance retornou barras fora da janela");
            fetched += decoded.size();
            persisted += storage.saveClosed(info,decoded);
            pages++;
            long last=decoded.getLast().openTime();
            long next=Math.addExact(last, frame.millis());
            if(next<=cursor) throw new IllegalStateException("Paginacao Binance sem progresso");
            cursor=next;
            if (decoded.size()<settings.pageLimit()) break;
        }
        if(cursor<=effectiveUntil && pages==settings.maxPagesPerRequest())
            log.warn("Sync parcial (limite de paginas): {} {} ate {}",symbol,intervalCode,cursor);
        return new SyncResult(symbol, intervalCode, from, effectiveUntil, fetched, persisted, pages);
    }
    public List<SyncResult> bootstrap() {
        long now=clock.millis();
        long from=now-Duration.ofDays(settings.bootstrapDays()).toMillis();
        var results=new java.util.ArrayList<SyncResult>();
        refreshSymbols();
        for(String symbol:trading.symbols()) for(String interval:settings.intervals())
            results.add(sync(symbol,interval,from,now));
        return results;
    }
    public void ingestClosed(MarketCandle candle) {
        if (!candle.closed()) return;
        if (candle.closeTime() >= clock.millis()) return;
        if (!settings.intervals().contains(candle.interval().code())) return;
        storage.saveClosed(checked(candle.symbol()),List.of(candle));
    }
    public List<SyncResult> reconcile() {
        long now=clock.millis();
        var results=new java.util.ArrayList<SyncResult>();
        for(String symbol:trading.symbols()) for(String interval:settings.intervals()) {
            long lookback=Math.max(Duration.ofMinutes(settings.reconcileLookbackMinutes()).toMillis(),
                2*CandleInterval.parse(interval).millis());
            results.add(sync(symbol,interval,now-lookback,now));
        }
        return results;
    }
    /** Snapshot historico incluindo apenas candles com closeTime <= cutoff UTC epoch ms. */
    public List<MarketCandle> recentAtOrBefore(String symbol, String interval, int limit, long cutoff) {
        if (cutoff < 0) throw new IllegalArgumentException("cutoff invalido");
        var info = checked(symbol);
        var id = instruments.findBySymbolAndMarketType(info.symbol(), info.market()).orElseThrow().getId();
        var data = candles.recentClosedAtOrBefore(id, CandleInterval.parse(interval).code(),
                cutoff, PageRequest.of(0, limit));
        return data.stream().map(c -> new MarketCandle(symbol, CandleInterval.parse(interval),
                c.getOpenTimeEpochMs(), c.getCloseTimeEpochMs(), c.getOpenPrice(), c.getHighPrice(),
                c.getLowPrice(), c.getClosePrice(), c.getBaseVolume(), c.getQuoteVolume(),
                c.getNumberOfTrades()==null?0:c.getNumberOfTrades(), c.getTakerBuyBaseVolume(),
                c.getTakerBuyQuoteVolume()==null?java.math.BigDecimal.ZERO:c.getTakerBuyQuoteVolume(),
                c.isClosed())).toList();
    }

    public List<MarketCandle> recent(String symbol,String interval,int limit) {
        var info=checked(symbol);
        var id=instruments.findBySymbolAndMarketType(info.symbol(),info.market()).orElseThrow().getId();
        return candles.findByInstrument_IdAndTimeframeAndClosedTrueOrderByOpenTimeEpochMsDesc(
                id,CandleInterval.parse(interval).code(),PageRequest.of(0,limit))
                .stream().map(c -> new MarketCandle(symbol,CandleInterval.parse(interval),
                    c.getOpenTimeEpochMs(),c.getCloseTimeEpochMs(),c.getOpenPrice(),c.getHighPrice(),
                    c.getLowPrice(),c.getClosePrice(),c.getBaseVolume(),c.getQuoteVolume(),
                    c.getNumberOfTrades()==null?0:c.getNumberOfTrades(),
                    c.getTakerBuyBaseVolume(),c.getTakerBuyQuoteVolume()==null?
                        java.math.BigDecimal.ZERO:c.getTakerBuyQuoteVolume(),c.isClosed()))
                .toList();
    }
}
