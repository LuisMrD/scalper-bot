package br.com.tradingbot.strategy.independent;

/**
 * Frozen BEFORE prospective OOS evaluation. Exactly one position and cash
 * balance are shared between the original LONG and MIDAS SHORT detectors.
 */
public enum SharedEntryBookPolicy {
    CONTROL(EntrySelectionPolicy.BASELINE, EntrySelectionPolicy.BASELINE),
    CANDIDATE_A(EntrySelectionPolicy.WEEKDAYS_ONLY, EntrySelectionPolicy.COMBINED_045),
    CANDIDATE_B(EntrySelectionPolicy.WEEKDAYS_ONLY, EntrySelectionPolicy.COMBINED_050);

    private final EntrySelectionPolicy longGate;
    private final EntrySelectionPolicy shortGate;
    SharedEntryBookPolicy(EntrySelectionPolicy longGate, EntrySelectionPolicy shortGate) {
        this.longGate = longGate;
        this.shortGate = shortGate;
    }
    public EntrySelectionPolicy longGate() { return longGate; }
    public EntrySelectionPolicy shortGate() { return shortGate; }
    public EntrySelectionPolicy forSide(IndependentSetupSignal.Side side) {
        return side == IndependentSetupSignal.Side.SHORT ? shortGate : longGate;
    }
}
