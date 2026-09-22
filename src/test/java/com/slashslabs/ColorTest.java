package com.slashslabs;

import com.slashslabs.color.ColorMath;
import com.slashslabs.color.PaletteFit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColorTest {
    @Test
    void labOfWhiteAndBlack() {
        double[] w = ColorMath.lab(0xFFFFFF), k = ColorMath.lab(0x000000);
        assertEquals(100, w[0], 0.1);
        assertEquals(0, w[1], 0.5);
        assertEquals(0, k[0], 0.1);
    }

    @Test
    void nearestPicksClosestGreen() {
        int[] palette = {0x91BD59, 0x79C05A, 0xBFB755};
        assertEquals(0, ColorMath.nearest(0x91BD59, palette));
        assertEquals(2, ColorMath.nearest(0xBFB755, palette));   // savanna
        assertEquals(1, ColorMath.nearest(0x59C93C, palette));   // jungle -> lush
        assertEquals(2, ColorMath.nearest(0x90814D, palette));   // badlands -> dry
    }

    @Test
    void multiplyMatchesTintIndex() {
        assertEquals(0xFF91BD59, ColorMath.multiply(0xFFFFFFFF, 0x91BD59));
        assertEquals(0xFF000000, ColorMath.multiply(0xFF000000, 0x91BD59));
        assertEquals(0x00000000, ColorMath.multiply(0x00FFFFFF, 0x000000) & 0xFF000000);
    }

    @Test
    void overCompositesAlpha() {
        assertEquals(0xFF112233, ColorMath.over(0xFF112233, 0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, ColorMath.over(0x00112233, 0xFFFFFFFF));
        int half = ColorMath.over(0x80000000, 0xFFFFFFFF);
        assertEquals(0xFF, half >>> 24);
        assertTrue(((half >> 16) & 0xFF) > 120 && ((half >> 16) & 0xFF) < 135);
    }

    @Test
    void paletteFitFindsClusters() {
        List<Integer> s = new ArrayList<>();
        for (int i = 0; i < 100; i++) s.add(0x91BD59);
        for (int i = 0; i < 60; i++) s.add(0xBFB755);
        for (int i = 0; i < 30; i++) s.add(0x59C93C);
        PaletteFit.Fit f2 = PaletteFit.fit(s, 2);
        assertEquals(2, f2.palette().length);
        PaletteFit.Fit f3 = PaletteFit.fit(s, 3);
        assertEquals(0.0, f3.meanError(), 1e-9);
        assertTrue(f2.meanError() > 0);
        assertTrue(PaletteFit.fit(List.of(), 3).palette().length == 0);
    }
}
