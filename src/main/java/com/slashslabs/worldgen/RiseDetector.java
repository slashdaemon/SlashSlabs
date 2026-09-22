package com.slashslabs.worldgen;

/**
 * The pure part of the smoothing algorithm (RESEARCH §3.3 steps 1-2): given an 18×18 height grid
 * (the chunk plus a one-column border, index {@code (z+1)*18 + (x+1)}), mark every centre column
 * that has a neighbour exactly one block higher. Rises of two or more are cliffs and are skipped;
 * {@link #NONE} marks a column with no usable surface.
 */
public final class RiseDetector {
    public static final int NONE = Integer.MIN_VALUE;
    public static final int SIZE = 18;

    private RiseDetector() {}

    public static int index(int lx, int lz) {
        return (lz + 1) * SIZE + (lx + 1);
    }

    /** Returns a 256-entry mask, index {@code lz*16 + lx}. */
    public static boolean[] detect(int[] heights, boolean diagonals) {
        if (heights.length != SIZE * SIZE) throw new IllegalArgumentException("need an 18x18 grid");
        boolean[] out = new boolean[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int h = heights[index(lx, lz)];
                if (h == NONE) continue;
                int want = h + 1;
                boolean rise = heights[index(lx + 1, lz)] == want || heights[index(lx - 1, lz)] == want
                        || heights[index(lx, lz + 1)] == want || heights[index(lx, lz - 1)] == want;
                if (!rise && diagonals) {
                    rise = heights[index(lx + 1, lz + 1)] == want || heights[index(lx - 1, lz - 1)] == want
                            || heights[index(lx - 1, lz + 1)] == want || heights[index(lx + 1, lz - 1)] == want;
                }
                out[lz * 16 + lx] = rise;
            }
        }
        return out;
    }
}
