package br.com.tradingbot.forex;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Free public Exness Standard historical ticks (actual BID/ASK, no orders). */
@Service
public final class ExnessForexDownloader {
    private static final long FIFTEEN=900_000L;
    private final Path folder;
    private final HttpClient http;
    public record DownloadReport(String pair,LocalDate fromUtc,LocalDate untilUtc,
            int monthsDownloaded,long ticksProcessed,int bars15m,
            long firstBarOpenEpochMs,long lastBarOpenEpochMs,
            String csvPath,String source,String warning) {}

    @Autowired
    public ExnessForexDownloader(@Value("${forex.csv-directory:data/forex}") String dir) {
        this(Path.of(dir),HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build());
    }
    ExnessForexDownloader(Path dir,HttpClient client) {
        folder=dir.toAbsolutePath().normalize();http=client;
    }
    static final class Acc {
        final long ms;
        BigDecimal bo,bh,bl,bc,ao,ah,al,ac;
        Acc(long ms,BigDecimal b,BigDecimal a) {
            this.ms=ms;bo=bh=bl=bc=b;ao=ah=al=ac=a;
        }
        void add(BigDecimal b,BigDecimal a) {
            bh=bh.max(b);bl=bl.min(b);bc=b;
            ah=ah.max(a);al=al.min(a);ac=a;
        }
        ForexBar bar(){return new ForexBar(ms,bo,bh,bl,bc,ao,ah,al,ac,null);}
    }
    public DownloadReport download(String pair,LocalDate from,LocalDate until) {
        if(!CsvForexHistoricalFeed.USD_QUOTE_PAIRS.contains(pair))
            throw new IllegalArgumentException("Unsupported Forex pair");
        if(from==null||until==null||from.isAfter(until)||from.plusDays(100).isBefore(until))
            throw new IllegalArgumentException("Use at most 101 days of history");
        if(until.isAfter(LocalDate.now(ZoneOffset.UTC).minusDays(2)))
            throw new IllegalArgumentException("Historical days must be closed");
        var bars=new TreeMap<Long,Acc>();
        long first=from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end=until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int months=0;long ticks=0;
        for(var month=YearMonth.from(from);!month.isAfter(YearMonth.from(until));
                month=month.plusMonths(1)) {
            ticks+=loadMonth(pair,month,first,end,bars);months++;
        }
        if(bars.size()<300)throw new IllegalArgumentException("Less than 300 genuine FX M15 bars");
        try {
            Files.createDirectories(folder);
            Path tmp=Files.createTempFile(folder,pair+"-exness-", ".tmp");
            Path destination=folder.resolve(pair+"-15m.csv");
            try(var out=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)) {
                out.write("open_time_utc,bid_open,bid_high,bid_low,bid_close,"
                        +"ask_open,ask_high,ask_low,ask_close,tick_volume\n");
                for(var item:bars.values()) {
                    ForexBar b=item.bar();
                    out.write(Instant.ofEpochMilli(b.openTime()).toString());
                    for(var value:List.of(b.bidOpen(),b.bidHigh(),b.bidLow(),b.bidClose(),
                            b.askOpen(),b.askHigh(),b.askLow(),b.askClose())) {
                        out.write(",");out.write(value.toPlainString());
                    }
                    out.write(",\n");
                }
            }
            try{ CsvForexHistoricalFeed.load(tmp); }
            catch(RuntimeException ex){ Files.deleteIfExists(tmp); throw ex; }
            try{Files.move(tmp,destination,StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex) {
                Files.move(tmp,destination,StandardCopyOption.REPLACE_EXISTING);
            }
            return new DownloadReport(pair,from,until,months,ticks,bars.size(),
                    bars.firstKey(),bars.lastKey(),
                    destination.toString(),"EXNESS_PUBLIC_STANDARD_BID_ASK_TICKS",
                    "Public indicative OTC quotes. No synthetic spreads/volume; swaps not included.");
        }catch(IOException e){throw new IllegalStateException("FX history CSV storage failed",e);}
    }
    private long loadMonth(String pair,YearMonth month,long first,long end,
            TreeMap<Long,Acc> bars) {
        String mm=String.format(Locale.ROOT,"%02d",month.getMonthValue());
        String y=String.valueOf(month.getYear());
        String url="https://ticks.ex2archive.com/ticks/"+pair+"/"+y+"/"+mm
                +"/Exness_"+pair+"_"+y+"_"+mm+".zip";
        var req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(4))
                .header("Accept","application/zip").GET().build();
        try {
            var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                if(response.statusCode()!=200)
                    throw new IllegalStateException("Exness archive HTTP "+
                            response.statusCode()+" for "+month+
                            "; no other provider's prices were substituted.");
                return readZip(body,pair,first,end,bars);
            }
        }catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Exness download interrupted for "+month,e);
        }catch(IOException e) {
            throw new IllegalStateException("Exness archive connection/format failed for "
                    +month,e);
        }
    }
    static long readZip(InputStream input,String pair,long first,long end,
            TreeMap<Long,Acc> bars) throws IOException {
        long count=0;boolean found=false;
        try(var zip=new ZipInputStream(input,StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory())continue;
                if(!entry.getName().toLowerCase(Locale.ROOT).endsWith(".csv")||found)
                    throw new IOException("Exactly one CSV expected in Exness monthly ZIP");
                found=true;
                var reader=new BufferedReader(new InputStreamReader(zip,StandardCharsets.UTF_8));
                var header=reader.readLine();
                if(header==null||!header.replace("\uFEFF","").replace("\"","")
                        .equalsIgnoreCase("Exness,Symbol,Timestamp,Bid,Ask"))
                    throw new IOException("Unexpected Exness CSV header");
                String row;long prior=Long.MIN_VALUE;
                while((row=reader.readLine())!=null) {
                    if(row.isBlank())continue;
                    String[] values=row.replace("\"","").split(",",-1);
                    if(values.length!=5||!pair.equalsIgnoreCase(values[1]))
                        throw new IOException("Invalid Exness symbol/record");
                    long when;
                    try {when=Instant.parse(values[2].replace(' ','T')).toEpochMilli();}
                    catch(RuntimeException ex){throw new IOException("Invalid UTC tick date",ex);}
                    if(when<prior)throw new IOException("Out-of-order Forex ticks");
                    prior=when;
                    if(++count>4_000_000)throw new IOException("Exness archive exceeds 4m ticks");
                    if(when<first||when>=end)continue;
                    BigDecimal bid,ask;
                    try {bid=new BigDecimal(values[3]);ask=new BigDecimal(values[4]);}
                    catch(NumberFormatException ex){throw new IOException("Invalid tick price",ex);}
                    if(bid.signum()<=0||ask.compareTo(bid)<0)
                        throw new IOException("Crossed/nonpositive Exness bid-ask");
                    long bucket=Math.floorDiv(when,FIFTEEN)*FIFTEEN;
                    var bar=bars.get(bucket);
                    if(bar==null)bars.put(bucket,new Acc(bucket,bid,ask));
                    else bar.add(bid,ask);
                }
                zip.closeEntry();
            }
        }
        if(!found)throw new IOException("Exness monthly ZIP contains no CSV");
        return count;
    }
}
