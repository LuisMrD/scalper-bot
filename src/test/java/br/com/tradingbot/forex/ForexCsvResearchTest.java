package br.com.tradingbot.forex;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForexCsvResearchTest {
    @TempDir Path directory;
    static final long START=Instant.parse("2024-01-08T00:00:00Z").toEpochMilli();
    private static BigDecimal b(String value){return new BigDecimal(value);}
    private static ForexBar bar(long t) {
        return new ForexBar(t,b("1.10000"),b("1.10020"),b("1.09980"),
                b("1.10000"),b("1.10010"),b("1.10030"),b("1.09990"),
                b("1.10010"),100L);
    }
    private static ForexPriceActionResearchService.Request request(long first,long last) {
        return new ForexPriceActionResearchService.Request("EURUSD",first,last,
                b("600"),b("0.5"),b("3"),10,b("3.5"),b("0.2"),
                b("1"),b("1000"),b("1000"),60,false);
    }
    @Test void dukascopyProductionConstructorMustBeMarkedForSpringInjection()
            throws Exception {
        var ctor=DukascopyForexDownloader.class.getConstructor(String.class);
        assertNotNull(ctor.getAnnotation(
                org.springframework.beans.factory.annotation.Autowired.class),
                "Multiple constructors exist: explicitly select the production one for Spring");
    }

    @Test void cashAndQuotesAreNotMixedWithBinanceMarket() {
        List<ForexBar> tape=new ArrayList<>();
        for(int i=0;i<300;i++)tape.add(bar(START+i*900_000L));
        ForexHistoricalFeed feed=symbol->{
            assertEquals("EURUSD",symbol);
            return List.copyOf(tape);
        };
        var research=new ForexPriceActionResearchService(feed);
        var output=research.backtest(request(START,START+299L*900_000L));
        assertEquals("FOREX_OTC_SPOT",output.market());
        assertEquals("FOREX_PA_V1_LONG_WEEKDAYS_AND_SHORT_PRICE_ACTION",output.strategy());
        assertEquals(300,output.candles());
        assertEquals(0,output.trades().size()); // no fabricated 23-day daily trend
        assertEquals(0,output.realizedUsd().compareTo(b("0")));
        assertEquals(0,output.feesUsd().compareTo(b("0")));
        assertFalse(output.openAtEnd());
        assertTrue(output.limitations().contains("No historical swap"));
    }
    @Test void bidAskAndTickVolumeAreExplicit() {
        assertThrows(IllegalArgumentException.class,()->new ForexBar(
                START,b("1.10"),b("1.11"),b("1.09"),b("1.10"),
                b("1.0999"),b("1.11"),b("1.09"),b("1.10"),1L));
        assertThrows(IllegalArgumentException.class,()->new ForexBar(
                START,b("1.10"),b("1.11"),b("1.09"),b("1.10"),
                b("1.1001"),b("1.1101"),b("1.0901"),b("1.1001"),-1L));
        assertThrows(IllegalArgumentException.class,()->new ForexBar(
                START+1,b("1.10"),b("1.11"),b("1.09"),b("1.10"),
                b("1.1001"),b("1.1101"),b("1.0901"),b("1.1001"),1L));
    }
    @Test void strictCsvRefusesMissingMidweekBar() throws Exception {
        var file=directory.resolve("EURUSD-15m.csv");
        var rows=new ArrayList<String>();
        rows.add("open_time_utc,bid_open,bid_high,bid_low,bid_close,"
                +"ask_open,ask_high,ask_low,ask_close,tick_volume");
        for(int i=0;i<300;i++) {
            long time=START+(i+(i>=5?1:0))*900_000L;
            rows.add(Instant.ofEpochMilli(time)+",1.10000,1.10020,1.09980,"
                    +"1.10000,1.10010,1.10030,1.09990,1.10010,100");
        }
        Files.write(file,rows);
        assertThrows(IllegalArgumentException.class,
                ()->CsvForexHistoricalFeed.load(file),"Midweek missing bars must not be filled");
    }
    @Test void strictCsvParsesCompleteUtcBarsWithoutFabricatingVolume() throws Exception {
        var file=directory.resolve("EURUSD-15m.csv");
        var rows=new ArrayList<String>();
        rows.add("open_time_utc,bid_open,bid_high,bid_low,bid_close,"
                +"ask_open,ask_high,ask_low,ask_close,tick_volume");
        for(int i=0;i<300;i++)
            rows.add(Instant.ofEpochMilli(START+i*900_000L)
                    +",1.10000,1.10020,1.09980,1.10000,"
                    +"1.10010,1.10030,1.09990,1.10010,");
        Files.write(file,rows);
        var data=CsvForexHistoricalFeed.load(file);
        assertEquals(300,data.size());
        assertNull(data.getFirst().tickVolume());
        var feed=new CsvForexHistoricalFeed(directory.toString());
        assertEquals(300,feed.closed15m("EURUSD").size());
        assertThrows(IllegalArgumentException.class,()->feed.closed15m("BTCUSDT"));
        var withProxy=new ForexPriceActionResearchService.Request(
                "EURUSD",START,START+299L*900_000L,
                b("600"),b("0.5"),b("3"),10,b("3.5"),b("0.2"),
                b("1"),b("1000"),b("1000"),60,true);
        assertThrows(IllegalArgumentException.class,
                ()->new ForexPriceActionResearchService(feed).backtest(withProxy));
    }
    @Test void financialInputsAreNeverSilentlyFilled() {
        assertThrows(NullPointerException.class,
                ()->new ForexPriceActionResearchService.Request(
                        "EURUSD",START,START+900_000L,null,b("0.5"),b("3"),
                        10,b("3.5"),b("0.2"),b("1"),b("1000"),
                        b("1000"),60,false));
        assertThrows(IllegalArgumentException.class,
                ()->new ForexPriceActionResearchService.Request(
                        "EURUSDT",START,START+900_000L,b("600"),b("0.5"),b("3"),
                        10,b("3.5"),b("0.2"),b("1"),b("1000"),
                        b("1000"),60,false));
    }
}
