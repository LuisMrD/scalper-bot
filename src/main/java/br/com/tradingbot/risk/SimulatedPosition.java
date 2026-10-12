package br.com.tradingbot.risk;

import br.com.tradingbot.decision.PositionState.Side;
import java.math.BigDecimal;
import java.util.Objects;

/** Posicao hipotetica, sem sincronismo com a Binance. Stop vigente e anterior ao candle analisado. */
public record SimulatedPosition(Side side, BigDecimal entryPrice,
        BigDecimal initialStop, BigDecimal currentStop, BigDecimal quantity,
        long openedAtCloseTime) {
    public SimulatedPosition {
        Objects.requireNonNull(side, "side");
        if (side == Side.FLAT) throw new IllegalArgumentException("Posicao deve ser LONG ou SHORT");
        RiskPolicy.requirePositive(entryPrice, "entryPrice");
        RiskPolicy.requirePositive(initialStop, "initialStop");
        RiskPolicy.requirePositive(currentStop, "currentStop");
        RiskPolicy.requirePositive(quantity, "quantity");
        if (openedAtCloseTime < 0) throw new IllegalArgumentException("openedAtCloseTime invalido");
        if (side == Side.LONG) {
            if (initialStop.compareTo(entryPrice) >= 0 || currentStop.compareTo(initialStop) < 0)
                throw new IllegalArgumentException("Stop LONG invalido ou ampliado");
        } else if (initialStop.compareTo(entryPrice) <= 0 || currentStop.compareTo(initialStop) > 0) {
            throw new IllegalArgumentException("Stop SHORT invalido ou ampliado");
        }
    }
}
