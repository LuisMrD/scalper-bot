package br.com.tradingbot.forex;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No network, no guessing missing candles, no arbitrary API-supplied file path. */
@Component
public final class CsvForexHistoricalFeed implements ForexHistoricalFeed {
    static final Set<String> USD_QUOTE_PAIRS=Set.of("EURUSD","GBPUSD","AUDUSD","NZDUSD");
    private static final String HEADER="open_time_utc,bid_open,bid_high,bid_low,bid_close,"
            +"ask_open,ask_high,ask_low,ask_close,tick_volume";
    private final Path folder;
    public CsvForexHistoricalFeed(@Value("${forex.csv-directory:data/forex}") String folder) {
        this.folder=Path.of(folder).toAbsolutePath().normalize();
    }
    @Override public List<ForexBar> closed15m(String pair) {
        if(!USD_QUOTE_PAIRS.contains(pair))
            throw new IllegalArgumentException("Only USD-quoted spot pairs supported: "+pair);
        return load(folder.resolve(pair+"-15m.csv"));
    }
    public static List<ForexBar> load(Path source) {
        var bars=new ArrayList<ForexBar>();
        try(BufferedReader reader=Files.newBufferedReader(source,StandardCharsets.UTF_8)) {
            String first=reader.readLine();
            if(first==null || !first.replace("\uFEFF","").equals(HEADER))
                throw new IllegalArgumentException("Required exact UTF-8 header: "+HEADER);
            String line;long number=1;
            while((line=reader.readLine())!=null) {
                number++;
                if(line.isBlank())continue;
                String[] columns=line.split(",",-1);
                if(columns.length!=10)
                    throw new IllegalArgumentException("Expected 10 CSV columns on line "+number);
                try {
                    var at=OffsetDateTime.parse(columns[0]);
                    if(at.getOffset().getTotalSeconds()!=0)
                        throw new IllegalArgumentException("Timestamp must be UTC Z");
                    ForexBar bar=new ForexBar(at.toInstant().toEpochMilli(),
                            decimal(columns[1]),decimal(columns[2]),
                            decimal(columns[3]),decimal(columns[4]),
                            decimal(columns[5]),decimal(columns[6]),
                            decimal(columns[7]),decimal(columns[8]),
                            columns[9].isBlank()?null:Long.parseLong(columns[9]));
                    if(!bars.isEmpty())validateNext(bars.getLast(),bar);
                    bars.add(bar);
                    if(bars.size()>500_000)
                        throw new IllegalArgumentException("Too many candles (>500000)");
                }catch(RuntimeException e) {
                    throw new IllegalArgumentException("Invalid Forex CSV line "+number+
                            ": "+e.getMessage(),e);
                }
            }
        }catch(IOException e) {
            throw new IllegalArgumentException("Could not read Forex CSV: "+source,e);
        }
        if(bars.size()<300)
            throw new IllegalArgumentException("Need at least 300 quote bars for research");
        return List.copyOf(bars);
    }
    private static BigDecimal decimal(String text) {
        if(!text.matches("\\d+\\.\\d+"))
            throw new IllegalArgumentException("Expected positive dot-decimal prices");
        return new BigDecimal(text);
    }
    private static void validateNext(ForexBar before,ForexBar after) {
        long gap=after.openTime()-before.openTime();
        if(gap==ForexBar.FIFTEEN_MINUTES)return;
        var firstDay=Instant.ofEpochMilli(before.openTime())
                .atZone(ZoneOffset.UTC).getDayOfWeek();
        var secondDay=Instant.ofEpochMilli(after.openTime())
                .atZone(ZoneOffset.UTC).getDayOfWeek();
        // A weekend may be a Sun evening OR Mon morning reopen depending
        // on the broker and DST. Midweek missing bars are never accepted.
        boolean weekend=firstDay==DayOfWeek.FRIDAY
                &&(secondDay==DayOfWeek.SUNDAY||secondDay==DayOfWeek.MONDAY)
                &&gap>=24L*3600_000L &&gap<=72L*3600_000L;
        if(!weekend)
            throw new IllegalArgumentException("Non-weekend missing/overlapping 15m bars "
                    +before.openTime()+" -> "+after.openTime()
                    +"; provide a complete broker export, never interpolate");
    }
}
