package br.com.tradingbot.integration.binance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.tradingbot.config.*;
import br.com.tradingbot.market.service.MarketDataService;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class BinanceKlineWebSocketTest {
    @Test void combinesSymbolsAndIntervalsIntoSingleReadOnlyStream() {
        var urls=new BinanceProperties(URI.create("https://demo-fapi.binance.com"),
                URI.create("wss://demo-fstream.binance.com"));
        var trading=new TradingProperties(List.of("BTCUSDT","ETHUSDT"),
                new TradingProperties.Execution(ExecutionMode.TESTNET,false),
                new TradingProperties.Risk(null,null,null,null));
        var config=new MarketDataProperties(List.of("1m","5m"),true,false,7,1000,30,120,"/market/stream");
        var socket=new BinanceKlineWebSocket(urls,trading,config,
                mock(BinanceKlineDecoder.class),mock(MarketDataService.class));
        var uri=socket.streamUri().toString();
        assertTrue(uri.startsWith("wss://demo-fstream.binance.com/market/stream?streams="));
        assertTrue(uri.contains("btcusdt@kline_1m"));
        assertTrue(uri.contains("ethusdt@kline_5m"));
        socket.stop();
    }
}
