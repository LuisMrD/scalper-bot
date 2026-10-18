package br.com.tradingbot.forex;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tukaani.xz.LZMAInputStream;

/**
 * Free, public Dukascopy BID/ASK M1 candle data, no token required.
 * Daily .bi5 LZMA-alone, 24-byte >IIIIIf: seconds, open, close, low, high, volume.
 * Emits only 15m bars with all 15 M1 minutes present, never interpolates.
 */
@Service
public final class DukascopyForexDownloader {
    static final String BASE="https://datafeed.dukascopy.com/datafeed/";
    private final Path root;
    private final HttpClient client;

    public record DownloadReport(String pair,LocalDate fromUtc,LocalDate untilUtc,
            int daysRequested,int daysWithQuotes,int bars15m,String csvPath,
            String source,String warning) {}
    record Minute(int minute,BigDecimal open,BigDecimal high,BigDecimal low,BigDecimal close) {}

    @org.springframework.beans.factory.annotation.Autowired
    public DukascopyForexDownloader(@Value("${forex.csv-directory:data/forex}") String root) {
        this(Path.of(root),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build());
    }
    DukascopyForexDownloader(Path root,HttpClient client) {
        this.root=root.toAbsolutePath().normalize();
        this.client=client;
    }
    public DownloadReport download(String pair,LocalDate start,LocalDate end) {
        if(!CsvForexHistoricalFeed.USD_QUOTE_PAIRS.contains(pair))
            throw new IllegalArgumentException("Unsupported FX pair");
        if(start==null||end==null||start.isAfter(end)
                ||start.plusDays(100).isBefore(end))
            throw new IllegalArgumentException("At most 100 days per import");
        if(end.isAfter(LocalDate.now(ZoneOffset.UTC).minusDays(2)))
            throw new IllegalArgumentException("Only fully historical UTC days");
        try {
            Files.createDirectories(root);
            Path target=root.resolve(pair+"-15m.csv");
            Path tmp=Files.createTempFile(root,pair+"-15m-",".tmp");
            int days=0,daysWithQuotes=0,bars=0;
            try(var writer=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)) {
                writer.write("open_time_utc,bid_open,bid_high,bid_low,bid_close,"
                        +"ask_open,ask_high,ask_low,ask_close,tick_volume\n");
                for(LocalDate day=start;!day.isAfter(end);day=day.plusDays(1)) {
                    days++;
                    var bids=downloadSide(pair,day,"BID");
                    var asks=downloadSide(pair,day,"ASK");
                    if(bids.isEmpty()&&asks.isEmpty())continue;
                    if(bids.isEmpty()||asks.isEmpty())
                        throw new IllegalArgumentException("Unpaired BID/ASK on "+day);
                    daysWithQuotes++;
                    var bmap=index(bids);
                    var amap=index(asks);
                    for(int m=0;m<1440;m+=15) {
                        Minute b=aggregate(bmap,m),a=aggregate(amap,m);
                        if(b==null&&a==null)continue;
                        if(b==null||a==null)
                            throw new IllegalArgumentException("Unpaired M15 quotes on "+day+" minute "+m);
                        long open=day.atStartOfDay(ZoneOffset.UTC).toInstant()
                                .toEpochMilli()+m*60_000L;
                        ForexBar bar=new ForexBar(open,
                                b.open(),b.high(),b.low(),b.close(),
                                a.open(),a.high(),a.low(),a.close(),null);
                        writer.write(Instant.ofEpochMilli(open).toString());
                        for(BigDecimal value:List.of(bar.bidOpen(),bar.bidHigh(),
                                bar.bidLow(),bar.bidClose(),bar.askOpen(),
                                bar.askHigh(),bar.askLow(),bar.askClose())) {
                            writer.write(",");
                            writer.write(value.toPlainString());
                        }
                        writer.write(",\n");
                        bars++;
                    }
                }
            }catch(Exception ex) {
                Files.deleteIfExists(tmp);
                throw ex;
            }
            if(bars<300) {
                Files.deleteIfExists(tmp);
                throw new IllegalArgumentException("Insufficient complete FX M15 candles: "+bars);
            }
            CsvForexHistoricalFeed.load(tmp);
            try{
                Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(AtomicMoveNotSupportedException e) {
                Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);
            }
            return new DownloadReport(pair,start,end,days,daysWithQuotes,bars,
                    target.toString(),"DUKASCOPY_PUBLIC_BID_ASK_M1",
                    "No trade/taker/tick volume manufactured; missing M1 makes import fail");
        }catch(IOException e) {
            throw new IllegalStateException("FX import file I/O failed",e);
        }
    }
    private static Map<Integer,Minute> index(List<Minute> bars) {
        var map=new HashMap<Integer,Minute>();
        for(var bar:bars)
            if(map.put(bar.minute(),bar)!=null)
                throw new IllegalArgumentException("Duplicate M1 minute");
        return map;
    }
    private List<Minute> downloadSide(String pair,LocalDate day,String side) {
        String url=BASE+pair+"/"+day.getYear()+"/"
                +String.format(Locale.ROOT,"%02d",day.getMonthValue()-1)+"/"
                +String.format(Locale.ROOT,"%02d",day.getDayOfMonth())+"/"
                +side+"_candles_min_1.bi5";
        var request=HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Accept","application/octet-stream").GET().build();
        try {
            for(int trial=0;trial<3;trial++) {
                var response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
                int status=response.statusCode();
                if(status==404 ||status==204)return List.of();
                if(status==429||status>=500) {
                    if(trial==2)throw new IOException("Dukascopy HTTP "+status+" "+day);
                    Thread.sleep(1200L*(trial+1));continue;
                }
                if(status!=200)throw new IOException("Dukascopy HTTP "+status+" "+day);
                if(response.body().length==0)return List.of();
                if(response.body().length>15_000_000)
                    throw new IOException("Oversized Dukascopy M1 file");
                return decodeDailyM1(response.body(),day);
            }
            throw new IOException("Dukascopy retries exhausted");
        }catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching FX quotes",e);
        }catch(IOException e) {
            throw new IllegalStateException("Failed fetching "+url,e);
        }
    }
    static List<Minute> decodeDailyM1(byte[] compressed,LocalDate day) {
        var result=new ArrayList<Minute>();
        try(var input=new DataInputStream(new LZMAInputStream(new ByteArrayInputStream(compressed)))) {
            int prev=-1;
            while(true) {
                int sec;
                try {sec=input.readInt();}
                catch(EOFException end) {break;}
                long time=Integer.toUnsignedLong(sec);
                if(time>=86400||time%60!=0)throw new IOException("Invalid M1 UTC second offset");
                int minute=(int)(time/60);
                int rawOpen=input.readInt(),rawClose=input.readInt();
                int rawLow=input.readInt(),rawHigh=input.readInt();
                input.readFloat();
                if(minute<=prev)throw new IOException("Duplicate/out-of-order M1");
                prev=minute;
                result.add(new Minute(minute,
                        BigDecimal.valueOf(Integer.toUnsignedLong(rawOpen),5),
                        BigDecimal.valueOf(Integer.toUnsignedLong(rawHigh),5),
                        BigDecimal.valueOf(Integer.toUnsignedLong(rawLow),5),
                        BigDecimal.valueOf(Integer.toUnsignedLong(rawClose),5)));
                if(result.size()>1440)throw new IOException("Invalid M1 day length");
            }
        }catch(IOException e) {
            throw new IllegalArgumentException("Invalid Dukascopy M1 payload on "+day,e);
        }
        return result;
    }
    private static Minute aggregate(Map<Integer,Minute> source,int start) {
        boolean any=false;
        for(int m=start;m<start+15;m++)any |= source.containsKey(m);
        if(!any)return null;
        Minute first=source.get(start),last=source.get(start+14);
        if(first==null||last==null)
            throw new IllegalArgumentException("Partial M15 minute bucket "+start);
        BigDecimal high=first.high(),low=first.low();
        for(int m=start;m<start+15;m++) {
            Minute b=source.get(m);
            if(b==null)throw new IllegalArgumentException("Missing minute "+m+" in M15 bucket");
            high=high.max(b.high());
            low=low.min(b.low());
        }
        return new Minute(start,first.open(),high,low,last.close());
    }
}
