package org.example.stockwatch247.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicPivot;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.PivotType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContainedSwingHierarchyTest {
    @Test void localUpdatesPreserveEveryContainedGeometryAndVisitOnlyLinearNeighborhoods() {
        Random random = new Random(419);
        for (int sample = 0; sample < 60; sample++) {
            List<HarmonicPivot> pivots = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                double price = 100 + random.nextInt(45) + (index % 2 == 0 ? -30 : 30);
                pivots.add(new HarmonicPivot(index, 1000L + index, price,
                        index % 2 == 0 ? PivotType.LOW : PivotType.HIGH, 1001L + index));
            }
            for (int window : new int[]{5, 9, 13}) {
                Set<List<Integer>> expected = new HashSet<>();
                List<HarmonicPivot> current = pivots;
                while (true) {
                    collect(current, window, expected);
                    List<HarmonicPivot> next = removeSmallest(current);
                    if (next == current) break;
                    current = next;
                }
                Set<List<Integer>> actual = new HashSet<>();
                AtomicInteger visited = new AtomicInteger();
                ContainedSwingHierarchy.scan(pivots, window, affected -> {
                    visited.addAndGet(affected.size());
                    collect(affected, window, actual);
                });
                assertThat(actual).as("sample %s, window %s", sample, window).isEqualTo(expected);
                assertThat(visited.get()).isLessThanOrEqualTo(pivots.size() * (window + 1));
            }
        }
    }

    private List<HarmonicPivot> removeSmallest(List<HarmonicPivot> pivots) {
        int remove = -1;
        double smallest = Double.POSITIVE_INFINITY;
        for (int index = 1; index + 2 < pivots.size(); index++) {
            double low = Math.min(pivots.get(index - 1).price(), pivots.get(index + 2).price());
            double high = Math.max(pivots.get(index - 1).price(), pivots.get(index + 2).price());
            double first = pivots.get(index).price();
            double second = pivots.get(index + 1).price();
            if (first < low || first > high || second < low || second > high) continue;
            double size = Math.abs(first - second) / Math.max(Math.abs(first), Math.abs(second));
            if (size < smallest) { smallest = size; remove = index; }
        }
        if (remove < 0) return pivots;
        List<HarmonicPivot> result = new ArrayList<>(pivots);
        result.subList(remove, remove + 2).clear();
        return result;
    }

    private void collect(List<HarmonicPivot> pivots, int window, Set<List<Integer>> geometries) {
        for (int end = 3; end < pivots.size(); end++) {
            geometries.add(pivots.subList(end - 3, end + 1).stream().map(HarmonicPivot::index).toList());
        }
        for (int endpoint = 4; endpoint < pivots.size(); endpoint++) {
            enumerate(pivots, Math.max(0, endpoint - window + 1), endpoint, new ArrayList<>(), geometries);
        }
    }

    private void enumerate(List<HarmonicPivot> pivots, int next, int endpoint,
                           List<Integer> selected, Set<List<Integer>> geometries) {
        if (selected.size() == 4) {
            selected.add(endpoint);
            boolean valid = true;
            for (int leg = 1; leg < selected.size(); leg++) {
                HarmonicPivot first = pivots.get(selected.get(leg - 1));
                HarmonicPivot last = pivots.get(selected.get(leg));
                if (first.type() == last.type()) valid = false;
                for (int inside = selected.get(leg - 1) + 1; inside < selected.get(leg); inside++) {
                    double price = pivots.get(inside).price();
                    if (price < Math.min(first.price(), last.price()) || price > Math.max(first.price(), last.price())) valid = false;
                }
            }
            if (valid) geometries.add(selected.stream().map(index -> pivots.get(index).index()).toList());
            selected.removeLast();
            return;
        }
        for (int index = next; index < endpoint; index++) {
            selected.add(index);
            enumerate(pivots, index + 1, endpoint, selected, geometries);
            selected.removeLast();
        }
    }
}
