package br.com.tradingbot.market.model;

import java.time.Duration;
import java.util.Arrays;

/** Intervalos fixos suportados por esta etapa (sem 1M, cuja duracao e variavel). */
public enum CandleInterval {
    M1("1m", Duration.ofMinutes(1)), M3("3m", Duration.ofMinutes(3)),
    M5("5m", Duration.ofMinutes(5)), M15("15m", Duration.ofMinutes(15)),
    M30("30m", Duration.ofMinutes(30)), H1("1h", Duration.ofHours(1)),
    H2("2h", Duration.ofHours(2)), H4("4h", Duration.ofHours(4)),
    H6("6h", Duration.ofHours(6)), H8("8h", Duration.ofHours(8)),
    H12("12h", Duration.ofHours(12)), D1("1d", Duration.ofDays(1));

    private final String code;
    private final Duration duration;
    CandleInterval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }
    public String code() { return code; }
    public long millis() { return duration.toMillis(); }
    public static CandleInterval parse(String value) {
        return Arrays.stream(values()).filter(i -> i.code.equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Timeframe nao suportado: " + value));
    }
}
