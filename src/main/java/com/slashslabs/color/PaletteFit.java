package com.slashslabs.color;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks a k-entry grass palette from surveyed rise-edge colours: weighted k-medoids in CIELAB,
 * so every suggested entry is a colour that really occurs. Pure Java (unit tested).
 */
public final class PaletteFit {
    private PaletteFit() {}

    public record Fit(int[] palette, double meanError, double maxError) {}

    public static Fit fit(List<Integer> samples, int k) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int c : samples) counts.merge(c & 0xF8F8F8, 1, Integer::sum); // 5 bits per channel keeps n small
        List<Integer> colors = new ArrayList<>(counts.keySet());
        colors.sort(null);
        int n = colors.size();
        if (n == 0) return new Fit(new int[0], 0, 0);
        k = Math.min(k, n);
        double[][] lab = new double[n][];
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            lab[i] = ColorMath.lab(colors.get(i));
            w[i] = counts.get(colors.get(i));
        }
        // Greedy init: the most frequent colour, then repeatedly the colour that most reduces cost.
        int[] med = new int[k];
        int first = 0;
        for (int i = 1; i < n; i++) if (w[i] > w[first]) first = i;
        med[0] = first;
        for (int m = 1; m < k; m++) {
            int best = -1;
            double bestCost = Double.MAX_VALUE;
            for (int cand = 0; cand < n; cand++) {
                med[m] = cand;
                double c = cost(lab, w, med, m + 1);
                if (c < bestCost) {
                    bestCost = c;
                    best = cand;
                }
            }
            med[m] = best;
        }
        // Swap refinement until no single swap improves the cost.
        double cur = cost(lab, w, med, k);
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int m = 0; m < k; m++) {
                int keep = med[m];
                for (int cand = 0; cand < n; cand++) {
                    med[m] = cand;
                    double c = cost(lab, w, med, k);
                    if (c + 1e-9 < cur) {
                        cur = c;
                        keep = cand;
                        improved = true;
                    }
                }
                med[m] = keep;
            }
        }
        int[] palette = new int[k];
        for (int m = 0; m < k; m++) palette[m] = colors.get(med[m]);
        long total = 0;
        double max = 0;
        for (int i = 0; i < n; i++) {
            total += w[i];
            max = Math.max(max, nearestDist(lab, med, k, i));
        }
        return new Fit(palette, cur / total, max);
    }

    private static double cost(double[][] lab, int[] w, int[] med, int k) {
        double c = 0;
        for (int i = 0; i < lab.length; i++) c += w[i] * nearestDist(lab, med, k, i);
        return c;
    }

    private static double nearestDist(double[][] lab, int[] med, int k, int i) {
        double best = Double.MAX_VALUE;
        for (int m = 0; m < k; m++) {
            double[] p = lab[i], q = lab[med[m]];
            double d = Math.sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]));
            if (d < best) best = d;
        }
        return best;
    }
}
