package org.javaup.booking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure planning against one capacity snapshot. A plan does not hold a slot. */
final class AssignmentPlanner {
    record Candidate(long slotId, double distanceKm) {}
    record Demand(long orderId, List<Candidate> candidates) {}
    record Match(long orderId, long slotId, double distanceKm) {}
    record Plan(List<Match> matches, int total, double totalDistanceKm, double maxDistanceKm) {}

    static Plan plan(List<Demand> demands, Map<Long, Integer> capacities, boolean constrainedFirst) {
        Map<Long, Integer> remaining = new HashMap<>(capacities);
        List<Demand> ordered = new ArrayList<>(demands);
        if (constrainedFirst) {
            ordered.sort(Comparator.comparingInt((Demand demand) -> demand.candidates().size())
                    .thenComparingLong(Demand::orderId));
        } else {
            ordered.sort(Comparator.comparingLong(Demand::orderId));
        }
        List<Match> matches = new ArrayList<>();
        for (Demand demand : ordered) {
            demand.candidates().stream()
                    .filter(candidate -> remaining.getOrDefault(candidate.slotId(), 0) > 0)
                    .min(Comparator.comparingDouble(Candidate::distanceKm).thenComparingLong(Candidate::slotId))
                    .ifPresent(candidate -> {
                        remaining.compute(candidate.slotId(), (id, capacity) -> capacity - 1);
                        matches.add(new Match(demand.orderId(), candidate.slotId(), candidate.distanceKm()));
                    });
        }
        return new Plan(List.copyOf(matches), demands.size(),
                matches.stream().mapToDouble(Match::distanceKm).sum(),
                matches.stream().mapToDouble(Match::distanceKm).max().orElse(0));
    }
}
