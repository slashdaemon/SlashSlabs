package com.slashslabs;

import com.slashslabs.worldgen.RiseDetector;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RiseDetectorTest {
    static int[] flat(int h) {
        int[] g = new int[RiseDetector.SIZE * RiseDetector.SIZE];
        Arrays.fill(g, h);
        return g;
    }

    static void set(int[] g, int lx, int lz, int h) {
        g[RiseDetector.index(lx, lz)] = h;
    }

    @Test
    void flatHasNoRises() {
        boolean[] r = RiseDetector.detect(flat(64), false);
        for (boolean b : r) assertFalse(b);
    }

    @Test
    void oneBlockStepMarksLowerSideOnly() {
        int[] g = flat(64);
        for (int lz = -1; lz <= 16; lz++) for (int lx = 8; lx <= 16; lx++) set(g, lx, lz, 65);
        boolean[] r = RiseDetector.detect(g, false);
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++)
                assertEquals(lx == 7, r[lz * 16 + lx], "x=" + lx + " z=" + lz);
    }

    @Test
    void cliffsAreSkipped() {
        int[] g = flat(64);
        for (int lz = -1; lz <= 16; lz++) for (int lx = 8; lx <= 16; lx++) set(g, lx, lz, 66);
        for (boolean b : RiseDetector.detect(g, false)) assertFalse(b);
    }

    @Test
    void borderColumnsCountAsNeighbours() {
        int[] g = flat(64);
        set(g, -1, 5, 65);   // west border column
        set(g, 16, 9, 65);   // east border column
        boolean[] r = RiseDetector.detect(g, false);
        assertTrue(r[5 * 16]);
        assertTrue(r[9 * 16 + 15]);
        int count = 0;
        for (boolean b : r) if (b) count++;
        assertEquals(2, count);
    }

    @Test
    void diagonalsOnlyWhenEnabled() {
        int[] g = flat(64);
        set(g, 6, 6, 65);
        assertFalse(RiseDetector.detect(g, false)[5 * 16 + 5]);
        assertTrue(RiseDetector.detect(g, true)[5 * 16 + 5]);
        assertTrue(RiseDetector.detect(g, false)[6 * 16 + 5]);
    }

    @Test
    void noSurfaceIsIgnored() {
        int[] g = flat(64);
        set(g, 3, 3, RiseDetector.NONE);
        set(g, 4, 3, RiseDetector.NONE + 1); // never a "rise" from NONE
        assertFalse(RiseDetector.detect(g, false)[3 * 16 + 3]);
    }

    @Test
    void singlePassDoesNotCascade() {
        int[] g = flat(60);
        for (int lx = 0; lx < 16; lx++) for (int lz = -1; lz <= 16; lz++) set(g, lx, lz, 60 + lx);
        set(g, -1, 0, 60);
        boolean[] r = RiseDetector.detect(g, false);
        // every column has its east neighbour exactly one higher (a staircase) -> each gets one slab
        for (int lx = 0; lx < 15; lx++) assertTrue(r[lx]);
    }
}
