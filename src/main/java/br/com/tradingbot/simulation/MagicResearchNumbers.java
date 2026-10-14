package br.com.tradingbot.simulation;

import java.util.Objects;

/**
 * Experimental decimal family 3/6/9. Used only for selecting NEW research
 * parameters, never to imply causality or statistical significance.
 */
public final class MagicResearchNumbers {
    private MagicResearchNumbers() {}

    public static boolean allowedWholeNumber(long value) {
        if (value <= 0) return false;
        while (value % 10 == 0) value /= 10;
        return value == 3 || value == 6 || value == 9;
    }

    public static int requireHours(int hours) {
        if (!allowedWholeNumber(hours)) {
            throw new IllegalArgumentException("Research horizon must use the 3/6/9 family");
        }
        return hours;
    }

    public static int requireMinutes(int minutes) {
        if (!allowedWholeNumber(minutes)) {
            throw new IllegalArgumentException("Research wait must use the 3/6/9 family");
        }
        return minutes;
    }
}
