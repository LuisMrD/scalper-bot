package br.com.tradingbot.forex;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ExnessForexDownloaderTest {
    private byte[] zip(String csv) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var archive=new ZipOutputStream(bytes)) {
            archive.putNextEntry(new ZipEntry("Exness_EURUSD_2024_03.csv"));
            archive.write(csv.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test void realBidAskTicksAggregateInCausalM15BucketsWithoutSynthesizedSpread()
            throws Exception {
        String csv="\"Exness\",\"Symbol\",\"Timestamp\",\"Bid\",\"Ask\"\n"
                +"\"exness\",\"EURUSD\",\"2024-03-01 00:00:00.000Z\",1.08100,1.08112\n"
                +"\"exness\",\"EURUSD\",\"2024-03-01 00:04:00.000Z\",1.08130,1.08144\n"
                +"\"exness\",\"EURUSD\",\"2024-03-01 00:14:59.999Z\",1.08090,1.08104\n"
                +"\"exness\",\"EURUSD\",\"2024-03-01 00:15:00.000Z\",1.08095,1.08110\n";
        var bars=new TreeMap<Long,ExnessForexDownloader.Acc>();
        long start=Instant.parse("2024-03-01T00:00:00Z").toEpochMilli();
        assertEquals(4L,ExnessForexDownloader.readZip(new ByteArrayInputStream(zip(csv)),
                "EURUSD",start,start+1_800_000L,bars));
        assertEquals(2,bars.size());
        var first=bars.get(start).bar();
        assertEquals("1.08100",first.bidOpen().toPlainString());
        assertEquals("1.08130",first.bidHigh().toPlainString());
        assertEquals("1.08090",first.bidLow().toPlainString());
        assertEquals("1.08104",first.askClose().toPlainString());
        assertNull(first.tickVolume());
        assertEquals("1.08095",bars.get(start+900_000L).bar().bidOpen().toPlainString());
    }

    @Test void rejectWrongPairAndUnsortedTicks() throws Exception {
        var rows="\"Exness\",\"Symbol\",\"Timestamp\",\"Bid\",\"Ask\"\n"
                +"exness,EURUSD,2024-03-01 00:10:00.000Z,1.0800,1.0802\n"
                +"exness,EURUSD,2024-03-01 00:01:00.000Z,1.0800,1.0802\n";
        assertThrows(java.io.IOException.class,()->ExnessForexDownloader.readZip(
                new ByteArrayInputStream(zip(rows)),"EURUSD",0,Long.MAX_VALUE,
                new TreeMap<>()));
        assertThrows(java.io.IOException.class,()->ExnessForexDownloader.readZip(
                new ByteArrayInputStream(zip(rows)),"GBPUSD",0,Long.MAX_VALUE,
                new TreeMap<>()));
    }
}
