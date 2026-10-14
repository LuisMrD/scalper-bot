package br.com.tradingbot.simulation;

import br.com.tradingbot.decision.DecisionResult;
import java.util.Objects;

/**
 * Prespecified research arms: compare the benefit of macro-selected LONGs
 * against rejecting ALL LONG entries while keeping SHORT setup unchanged.
 * No new numbers are selected after inspecting a holdout.
 */
public enum LongVersusShortPolicy {
    CONTROL(MagicMacroLongPolicy.NONE, false),
    SHORT_ONLY(MagicMacroLongPolicy.NONE, true),
    BOTH_UP_6H_DIP_3H(MagicMacroLongPolicy.BOTH_UP_6H_DIP_3H, false),
    BOTH_UP_9H_DIP_3H(MagicMacroLongPolicy.BOTH_UP_9H_DIP_3H, false);

    private final MagicMacroLongPolicy macro;
    private final boolean shortOnly;

    LongVersusShortPolicy(MagicMacroLongPolicy macro, boolean shortOnly) {
        this.macro = macro;
        this.shortOnly = shortOnly;
    }

    public MagicMacroLongPolicy macro() { return macro; }
    public boolean shortOnly() { return shortOnly; }

    /**
     * Only actionable RETEST LONG entries are rejected. Pending EXIT,
     * HOLD, WAIT, and every SHORT decision must pass unchanged.
     */
    public boolean rejectsLong(DecisionResult decision) {
        Objects.requireNonNull(decision, "decision");
        return shortOnly && decision.entry() == DecisionResult.Entry.LONG
                && decision.setup() == DecisionResult.Setup.BREAKOUT_RETEST
                && decision.positionAction() == DecisionResult.PositionAction.NONE;
    }
}
