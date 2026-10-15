package br.com.tradingbot.simulation;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MagicMacroLongPolicyTest {
    private static CrossAssetMacroMomentum.MagicSnapshot snap(String h3, String h6, String h9) {
        return new CrossAssetMacroMomentum.MagicSnapshot(899_999L,
                new BigDecimal(h3), new BigDecimal(h6), new BigDecimal(h9));
    }

    @Test
    void allResearchHorizonsUseOnlyTheMagicFamily() {
        for (long x : new long[]{3,6,9,30,60,90,300,600,900,3000,6000,9000}) {
            assertTrue(MagicResearchNumbers.allowedWholeNumber(x), "Missing magic parameter " + x);
        }
        for (long x : new long[]{0,-3,1,2,4,12,15,24,45,120,150}) {
            assertFalse(MagicResearchNumbers.allowedWholeNumber(x), "Should not allow " + x);
        }
        assertEquals(60, MagicResearchNumbers.requireMinutes(60));
        assertThrows(IllegalArgumentException.class, () -> MagicResearchNumbers.requireHours(24));
    }

    @Test
    void trendRulesRequireStrictlyPositiveBtcAndEthReturns() {
        var btc = snap("0.2", "0.3", "0.4");
        var eth = snap("0.1", "0.3", "-0.4");
        assertTrue(MagicMacroLongPolicy.NONE.allows(btc, eth));
        assertTrue(MagicMacroLongPolicy.BOTH_UP_3H.allows(btc, eth));
        assertTrue(MagicMacroLongPolicy.BOTH_UP_6H.allows(btc, eth));
        assertFalse(MagicMacroLongPolicy.BOTH_UP_9H.allows(btc, eth));
        assertFalse(MagicMacroLongPolicy.BOTH_UP_6H.allows(btc, snap("-0.1", "0", "0.2")));
    }

    @Test
    void dipRequiresBothAssetsDownOnThreeHoursButUpOnLongerHorizon() {
        var btc = snap("-0.2", "0.3", "0.5");
        var eth = snap("-0.5", "0.4", "0.3");
        assertTrue(MagicMacroLongPolicy.BOTH_UP_6H_DIP_3H.allows(btc, eth));
        assertTrue(MagicMacroLongPolicy.BOTH_UP_9H_DIP_3H.allows(btc, eth));
        assertFalse(MagicMacroLongPolicy.BOTH_UP_6H_DIP_3H.allows(
                btc, snap("0.5", "0.4", "0.3")));
        assertFalse(MagicMacroLongPolicy.BOTH_UP_9H_DIP_3H.allows(
                btc, snap("-0.5", "0.4", "-0.3")));
    }
}
