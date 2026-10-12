package br.com.tradingbot.decision;

import java.util.Objects;

/** Estado declarado para simulacao; nao consulta nem altera posicoes na Binance. */
public record PositionState(Side side) {
    public enum Side { FLAT, LONG, SHORT }

    public PositionState {
        Objects.requireNonNull(side, "side");
    }

    public static PositionState flat() { return new PositionState(Side.FLAT); }
}
