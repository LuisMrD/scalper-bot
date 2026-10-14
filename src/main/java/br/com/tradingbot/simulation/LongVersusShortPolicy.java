package br.com.tradingbot.simulation;

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
}
