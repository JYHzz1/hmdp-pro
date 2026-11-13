package org.javaup.booking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return summarize(matches, demands.size());
    }

    /** Augment the residual bipartite graph until no order can reach a free slot. */
    static Plan maximum(List<Demand> demands, Map<Long, Integer> capacities) {
        Map<Long, List<Candidate>> choices = new HashMap<>();
        for (Demand demand : demands) {
            choices.put(demand.orderId(), demand.candidates().stream()
                    .filter(candidate -> capacities.getOrDefault(candidate.slotId(), 0) > 0)
                    .sorted(Comparator.comparingDouble(Candidate::distanceKm).thenComparingLong(Candidate::slotId))
                    .toList());
        }
        Map<Long, Candidate> assignment = new HashMap<>();
        Map<Long, Set<Long>> occupants = new HashMap<>();
        for (long order : choices.keySet().stream().sorted().toList()) {
            augment(order, choices, capacities, assignment, occupants);
        }
        List<Match> matches = assignment.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new Match(entry.getKey(), entry.getValue().slotId(), entry.getValue().distanceKm()))
                .toList();
        return summarize(matches, demands.size());
    }

    private record Step(long order, Candidate target) {}

    private static void augment(long root, Map<Long, List<Candidate>> choices, Map<Long, Integer> capacities,
                                Map<Long, Candidate> assignment, Map<Long, Set<Long>> occupants) {
        var queue = new ArrayDeque<Long>();
        var visited = new HashSet<Long>();
        var predecessors = new HashMap<Long, Step>();
        visited.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            long order = queue.remove();
            for (Candidate candidate : choices.get(order)) {
                long slot = candidate.slotId();
                if (predecessors.putIfAbsent(slot, new Step(order, candidate)) != null) continue;
                Set<Long> assigned = occupants.computeIfAbsent(slot, ignored -> new LinkedHashSet<>());
                if (assigned.size() < capacities.get(slot)) {
                    moveAlongPath(slot, predecessors, assignment, occupants);
                    return;
                }
                for (long displaced : assigned) {
                    if (visited.add(displaced)) queue.add(displaced);
                }
            }
        }
    }

    private static void moveAlongPath(long slot, Map<Long, Step> predecessors,
                                      Map<Long, Candidate> assignment, Map<Long, Set<Long>> occupants) {
        while (true) {
            Step step = predecessors.get(slot);
            Candidate previous = assignment.put(step.order(), step.target());
            occupants.get(slot).add(step.order());
            if (previous == null) return;
            slot = previous.slotId();
            occupants.get(slot).remove(step.order());
        }
    }

    private static Plan summarize(List<Match> matches, int total) {
        return new Plan(List.copyOf(matches), total,
                matches.stream().mapToDouble(Match::distanceKm).sum(),
                matches.stream().mapToDouble(Match::distanceKm).max().orElse(0));
    }
}
