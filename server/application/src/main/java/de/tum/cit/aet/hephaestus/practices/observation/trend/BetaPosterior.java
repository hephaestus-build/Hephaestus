package de.tum.cit.aet.hephaestus.practices.observation.trend;

import java.util.Arrays;

/** Deterministic beta-posterior approximation for opportunity-indexed trends. */
final class BetaPosterior {

    static final int GRID_SIZE = 256;
    private static final double JEFFREYS_PRIOR = 0.5;

    private final double alpha;
    private final double beta;

    private BetaPosterior(double alpha, double beta) {
        this.alpha = alpha;
        this.beta = beta;
    }

    static BetaPosterior from(int opportunities, double metShareSum) {
        if (opportunities < 0 || metShareSum < 0 || metShareSum > opportunities) {
            throw new IllegalArgumentException("Invalid opportunity evidence");
        }
        return new BetaPosterior(JEFFREYS_PRIOR + metShareSum, JEFFREYS_PRIOR + opportunities - metShareSum);
    }

    double mean() {
        return alpha / (alpha + beta);
    }

    double variance() {
        double sum = alpha + beta;
        return (alpha * beta) / (sum * sum * (sum + 1.0));
    }

    Difference differenceFrom(BetaPosterior previous) {
        return differenceFrom(previous, GRID_SIZE);
    }

    Difference differenceFrom(BetaPosterior previous, int gridSize) {
        double[] currentMass = gridMass(gridSize);
        double[] previousMass = previous.gridMass(gridSize);
        double currentMean = gridMean(currentMass, gridSize);
        double previousMean = gridMean(previousMass, gridSize);
        double variance =
                gridVariance(currentMass, currentMean, gridSize) + gridVariance(previousMass, previousMean, gridSize);
        return new Difference(currentMean - previousMean, variance, currentMass, previousMass, gridSize);
    }

    private static double gridMean(double[] mass, int gridSize) {
        double mean = 0.0;
        for (int index = 0; index < gridSize; index++) {
            mean += ((index + 0.5) / gridSize) * mass[index];
        }
        return mean;
    }

    private static double gridVariance(double[] mass, double mean, int gridSize) {
        double variance = 0.0;
        for (int index = 0; index < gridSize; index++) {
            double deviation = ((index + 0.5) / gridSize) - mean;
            variance += deviation * deviation * mass[index];
        }
        return variance;
    }

    /**
     * The density on a midpoint grid, normalised to sum to one.
     *
     * <p>Only the unnormalised kernel is evaluated. The beta normaliser {@code B(α, β)} is one constant
     * subtracted from every grid point, and both the max-shift below and the final division by the total
     * remove any constant, so computing it (a log-gamma approximation) could not change a single output.
     *
     * <p>Midpoints, not cell edges: with the Jeffreys prior {@code α} and {@code β} can be below one, so
     * the density diverges at 0 and 1. A grid that evaluated the edges would produce infinities.
     */
    private double[] gridMass(int gridSize) {
        double[] logs = new double[gridSize];
        double max = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < gridSize; index++) {
            double point = (index + 0.5) / gridSize;
            logs[index] = (alpha - 1.0) * Math.log(point) + (beta - 1.0) * Math.log1p(-point);
            max = Math.max(max, logs[index]);
        }
        double[] mass = new double[gridSize];
        double sum = 0.0;
        for (int index = 0; index < gridSize; index++) {
            mass[index] = Math.exp(logs[index] - max);
            sum += mass[index];
        }
        final double total = sum;
        Arrays.setAll(mass, index -> mass[index] / total);
        return mass;
    }

    record Difference(double mean, double variance, double[] currentMass, double[] previousMass, int gridSize) {
        double probabilityAbove(double boundary) {
            return probabilityWhere(boundary, true);
        }

        double probabilityBelow(double boundary) {
            return probabilityWhere(boundary, false);
        }

        /**
         * The mass of the product grid on one side of {@code boundary}, in one pass instead of the full
         * {@code gridSize²} product.
         *
         * <p>For a fixed current point the condition is an interval on the previous point, so its mass is a
         * prefix sum. The interval's edge moves monotonically with the current point, which lets a single
         * forward pointer find it, so each grid point is visited once per axis rather than once per pair.
         * The comparisons are the same ones the pairwise form made, so cells on the edge fall the same way.
         */
        private double probabilityWhere(double boundary, boolean above) {
            double[] previousPrefix = new double[gridSize + 1];
            for (int previous = 0; previous < gridSize; previous++) {
                previousPrefix[previous + 1] = previousPrefix[previous] + previousMass[previous];
            }
            double probability = 0.0;
            int edge = 0;
            for (int current = 0; current < gridSize; current++) {
                double threshold = ((current + 0.5) / gridSize) - boundary;
                if (above) {
                    // current - previous > boundary  ⟺  previous < threshold
                    while (edge < gridSize && ((edge + 0.5) / gridSize) < threshold) {
                        edge++;
                    }
                    probability += currentMass[current] * previousPrefix[edge];
                } else {
                    // current - previous < boundary  ⟺  previous > threshold
                    while (edge < gridSize && ((edge + 0.5) / gridSize) <= threshold) {
                        edge++;
                    }
                    probability += currentMass[current] * (1.0 - previousPrefix[edge]);
                }
            }
            return probability;
        }
    }
}
