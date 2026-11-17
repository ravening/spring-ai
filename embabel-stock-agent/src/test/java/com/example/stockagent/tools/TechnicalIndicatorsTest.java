package com.example.stockagent.tools;

import com.example.stockagent.domain.Candle;
import com.example.stockagent.domain.PriceHistory;
import com.example.stockagent.domain.TechnicalResearch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TechnicalIndicatorsTest {

    @Test
    void computesSmaRsiAtrAndLevelsFromCandles() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double base = 100 + i;
            candles.add(new Candle(i * 86400L, base - 1, base + 2, base - 3, base, 1000 + i));
        }
        PriceHistory history = new PriceHistory("TEST", "1d", candles, 0, "test");

        TechnicalResearch result = TechnicalIndicators.analyse(history);

        assertEquals("TEST", result.symbol());
        assertEquals(149.5, result.sma20(), 0.01);
        assertEquals(134.5, result.sma50(), 0.01);
        assertNull(result.sma200(), "200-day SMA unavailable with only 60 candles");
        assertEquals(100.0, result.rsi14(), 0.01, "monotonic rise gives RSI 100");
        assertEquals(5.0, result.atr14(), 0.01);
        assertNotNull(result.support());
        assertNotNull(result.resistance());
        assertTrue(result.resistance() > result.support());
        assertFalse(result.interpretation().isBlank());
    }

    @Test
    void handlesTooLittleDataGracefully() {
        PriceHistory history = new PriceHistory("TEST", "1d",
                List.of(new Candle(0, 1, 2, 0.5, 1.5, 10)), 0, "test");
        TechnicalResearch result = TechnicalIndicators.analyse(history);
        assertNull(result.sma20());
        assertTrue(result.interpretation().contains("Not enough"));
    }
}
