package org.javaup.booking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentPlannerTest {
    @Test
    void rematchesExistingAssignmentsWhenBothGreedyPlansMissAnOrder() {
        var demands = List.of(demand(1, 1, 2), demand(2, 1, 3), demand(3, 1, 3));
        var capacities = Map.of(1L, 1, 2L, 1, 3L, 1);
        assertEquals(2, AssignmentPlanner.plan(demands, capacities, false).matches().size());
        assertEquals(2, AssignmentPlanner.plan(demands, capacities, true).matches().size());
        var maximum = AssignmentPlanner.maximum(demands, capacities);
        assertEquals(3, maximum.matches().size());
        assertEquals(2L, maximum.matches().get(0).slotId());
    }

    @Test
    void matchesExhaustiveSearchOnSmallSeededCapacityGraphs() {
        var random = new Random(407);
        for (int trial = 0; trial < 100; trial++) {
            var capacities = new java.util.HashMap<Long, Integer>();
            for (long slot = 1; slot <= 4; slot++) capacities.put(slot, random.nextInt(3));
            var demands = new java.util.ArrayList<AssignmentPlanner.Demand>();
            for (long order = 1; order <= 6; order++) {
                var candidates = new java.util.ArrayList<AssignmentPlanner.Candidate>();
                for (long slot = 1; slot <= 4; slot++) {
                    if (random.nextBoolean()) candidates.add(new AssignmentPlanner.Candidate(slot, slot));
                }
                demands.add(new AssignmentPlanner.Demand(order, candidates));
            }
            var plan = AssignmentPlanner.maximum(demands, capacities);
            assertEquals(exhaustive(demands, capacities, 0), plan.matches().size(), "trial " + trial);
            var used = new java.util.HashMap<Long, Integer>();
            for (var match : plan.matches()) {
                assertTrue(demands.get((int) match.orderId() - 1).candidates().stream()
                        .anyMatch(candidate -> candidate.slotId() == match.slotId()));
                assertTrue(used.merge(match.slotId(), 1, Integer::sum) <= capacities.get(match.slotId()));
            }
            assertEquals(plan.matches().size(), plan.matches().stream().map(AssignmentPlanner.Match::orderId).distinct().count());
        }
    }

    private int exhaustive(List<AssignmentPlanner.Demand> demands, Map<Long, Integer> remaining, int index) {
        if (index == demands.size()) return 0;
        int best = exhaustive(demands, remaining, index + 1);
        for (var candidate : demands.get(index).candidates()) {
            long slot = candidate.slotId();
            int capacity = remaining.get(slot);
            if (capacity == 0) continue;
            remaining.put(slot, capacity - 1);
            best = Math.max(best, 1 + exhaustive(demands, remaining, index + 1));
            remaining.put(slot, capacity);
        }
        return best;
    }

    private AssignmentPlanner.Demand demand(long id, long first, long second) {
        return new AssignmentPlanner.Demand(id, List.of(new AssignmentPlanner.Candidate(first, 1),
                new AssignmentPlanner.Candidate(second, 2)));
    }
}
