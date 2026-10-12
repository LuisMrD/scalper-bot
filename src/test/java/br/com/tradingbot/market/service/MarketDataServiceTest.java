package br.com.tradingbot.market.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import br.com.tradingbot.config.*;
import br.com.tradingbot.integration.binance.*;
import br.com.tradingbot.market.model.*;
import br.com.tradingbot.market.repository.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketDataServiceTest {
    @Test void paginatesAndSkipsOpenBar() {
        var trading=new TradingProperties(List.of("BTCUSDT"),
            new TradingProperties.Execution(ExecutionMode.DISABLED,false),
            new TradingProperties.Risk(null,null,null,null));
        var conf=new MarketDataProperties(List.of("1m"),false,false,7,2,5,120,"/stream");
        var client=mock(BinancePublicClient.class);
        var decoder=mock(BinanceKlineDecoder.class);
        var exchange=mock(BinanceExchangeInfo.class);
        var storage=mock(CandleStorageService.class);
        var instruments=mock(InstrumentRepository.class);
        var candles=mock(CandleRepository.class);
        var info=new BinanceExchangeInfo.SymbolInfo("BTCUSDT",InstrumentMarket.USD_M_PERPETUAL,"BTC","USDT");
        when(client.exchangeInfo()).thenReturn("exchange");
        when(exchange.decode(eq("exchange"),eq(Set.of("BTCUSDT")))).thenReturn(Map.of("BTCUSDT",info));
        when(client.candles(eq("BTCUSDT"),eq(CandleInterval.M1),anyLong(),anyLong(),eq(2)))
            .thenReturn("page1","page2");
        when(decoder.rest(eq("BTCUSDT"),eq(CandleInterval.M1),eq("page1"),anyLong()))
            .thenReturn(List.of(bar(60000,true),bar(120000,true)));
        when(decoder.rest(eq("BTCUSDT"),eq(CandleInterval.M1),eq("page2"),anyLong()))
            .thenReturn(List.of(bar(180000,false)));
        when(storage.saveClosed(eq(info),anyList())).thenAnswer(inv ->
                ((List<MarketCandle>)inv.getArgument(1)).stream().filter(MarketCandle::closed).toList().size());
        var service=new MarketDataService(trading,conf,client,exchange,decoder,storage,instruments,candles,
                Clock.fixed(Instant.ofEpochMilli(2000000),ZoneOffset.UTC));
        var result=service.sync("BTCUSDT","1m",60000,300000);
        assertEquals(3,result.fetched());
        assertEquals(2,result.persisted());
        assertEquals(2,result.pages());
        verify(client).candles("BTCUSDT",CandleInterval.M1,180000,300000,2);
    }
    private static MarketCandle bar(long t,boolean closed) {
        return new MarketCandle("BTCUSDT",CandleInterval.M1,t,t+59999,
            BigDecimal.valueOf(10),BigDecimal.valueOf(11),BigDecimal.valueOf(9),BigDecimal.valueOf(10),
            BigDecimal.valueOf(5),BigDecimal.valueOf(50),1,BigDecimal.valueOf(2),BigDecimal.valueOf(20),closed);
    }
}
