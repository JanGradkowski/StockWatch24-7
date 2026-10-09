package org.example.stockwatch247.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicPivot;

final class ContainedSwingHierarchy {
    private final PriorityQueue<Removal> removals = new PriorityQueue<>(
            Comparator.comparingDouble(Removal::size).thenComparingInt(removal -> removal.first().order));

    static void scan(List<HarmonicPivot> pivots, int window, Consumer<List<HarmonicPivot>> scan) {
        if (pivots.size() < 4) return;
        new ContainedSwingHierarchy().visit(pivots, window, scan);
    }

    private void visit(List<HarmonicPivot> pivots, int window, Consumer<List<HarmonicPivot>> scan) {
        List<Node> nodes = new ArrayList<>(pivots.size());
        Node previous = null;
        for (HarmonicPivot pivot : pivots) {
            Node node = new Node(pivot, nodes.size());
            node.previous = previous;
            if (previous != null) previous.next = node;
            nodes.add(node);
            previous = node;
        }
        nodes.forEach(this::offer);
        scan.accept(pivots);
        while (!removals.isEmpty()) {
            JobLeaseGuard.checkActive();
            Removal removal = removals.remove();
            Node first = removal.first();
            if (!first.active || first.version != removal.version()) continue;
            Node second = first.next;
            Node left = first.previous;
            Node right = second.next;
            first.active = false;
            second.active = false;
            left.next = right;
            right.previous = left;
            offer(left.previous);
            offer(left);
            offer(right);
            Node start = left;
            for (int count = 1; count < window && start.previous != null; count++) start = start.previous;
            List<HarmonicPivot> affected = new ArrayList<>(2 * window);
            for (Node node = start; node != right; node = node.next) affected.add(node.pivot);
            Node node = right;
            for (int count = 0; count < window && node != null; count++, node = node.next) affected.add(node.pivot);
            scan.accept(affected);
        }
    }

    private void offer(Node first) {
        if (first == null || !first.active) return;
        first.version++;
        Node left = first.previous;
        Node second = first.next;
        if (left == null || second == null || second.next == null) return;
        double low = Math.min(left.pivot.price(), second.next.pivot.price());
        double high = Math.max(left.pivot.price(), second.next.pivot.price());
        double firstPrice = first.pivot.price();
        double secondPrice = second.pivot.price();
        if (firstPrice < low || firstPrice > high || secondPrice < low || secondPrice > high) return;
        double size = Math.abs(firstPrice - secondPrice) / Math.max(Math.abs(firstPrice), Math.abs(secondPrice));
        removals.add(new Removal(first, first.version, size));
    }

    private static final class Node {
        final HarmonicPivot pivot;
        final int order;
        Node previous;
        Node next;
        int version;
        boolean active = true;

        Node(HarmonicPivot pivot, int order) {
            this.pivot = pivot;
            this.order = order;
        }
    }

    private record Removal(Node first, int version, double size) { }
}
