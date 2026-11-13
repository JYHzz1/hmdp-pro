package org.javaup.booking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/** Standalone planning experiment. No HTTP server or database required. */
public final class AssignmentBenchmark {
    private static final int ORDERS = 300;
    private static final int SLOTS = 100;
    private static final int CASES = 30;

    public static void main(String[] args) {
        System.out.println("scenario,strategy,assigned,orders,missed_vs_maximum,mean_km,p95_ms");
        run("scarce", 1, 3, 3, 407);
        run("mixed", 1, 8, 3, 408);
        run("abundant", 4, 8, 6, 409);
    }

    private static void run(String scenario, int low, int high, int capacity, long seed) {
        var random = new Random(seed);
        Map<Long, Integer> capacities = new HashMap<>();
        for (long slot = 0; slot < SLOTS; slot++) capacities.put(slot, capacity);
        var cases = new ArrayList<List<AssignmentPlanner.Demand>>();
        for (int sample = 0; sample < CASES; sample++) {
            var demands = new ArrayList<AssignmentPlanner.Demand>();
            for (long order = 0; order < ORDERS; order++) {
                int count = low + random.nextInt(high - low + 1);
                var candidates = random.longs(0, SLOTS).distinct().limit(count)
                        .mapToObj(slot -> new AssignmentPlanner.Candidate(slot, 1 + random.nextDouble() * 8)).toList();
                demands.add(new AssignmentPlanner.Demand(order, candidates));
            }
            cases.add(demands);
        }
        for (int i = 0; i < 15; i++) {
            var demands = cases.get(i);
            AssignmentPlanner.plan(demands, capacities, false);
            AssignmentPlanner.plan(demands, capacities, true);
            AssignmentPlanner.maximum(demands, capacities);
        }
        var nearest = new Measurement();
        var constrained = new Measurement();
        var maximum = new Measurement();
        for (int i = 0; i < CASES; i++) {
            var demands = cases.get(i);
            nearest.measure(i, () -> AssignmentPlanner.plan(demands, capacities, false));
            constrained.measure(i, () -> AssignmentPlanner.plan(demands, capacities, true));
            maximum.measure(i, () -> AssignmentPlanner.maximum(demands, capacities));
        }
        nearest.print(scenario, "nearest", maximum.assigned);
        constrained.print(scenario, "constrained", maximum.assigned);
        maximum.print(scenario, "maximum", maximum.assigned);
    }

    private static final class Measurement {
        private final double[] elapsed = new double[CASES];
        private int assigned;
        private double distance;

        void measure(int index, Supplier<AssignmentPlanner.Plan> strategy) {
            long start = System.nanoTime();
            var plan = strategy.get();
            elapsed[index] = (System.nanoTime() - start) / 1_000_000.0;
            assigned += plan.matches().size();
            distance += plan.totalDistanceKm();
        }

        void print(String scenario, String strategy, int optimum) {
            Arrays.sort(elapsed);
            System.out.printf(java.util.Locale.ROOT, "%s,%s,%d,%d,%d,%.3f,%.3f%n",
                    scenario, strategy, assigned, ORDERS * CASES, optimum - assigned,
                    assigned == 0 ? 0 : distance / assigned, elapsed[(int) Math.ceil(CASES * 0.95) - 1]);
        }
    }
}
