package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.ElliottProjectionScenario;
import org.example.stockwatch247.model.ElliottProjectionSet;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottProjectionScenarioStatus;
import org.example.stockwatch247.model.enums.ElliottProjectionSetStatus;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.ElliottProjectionScenarioRepository;
import org.example.stockwatch247.repository.ElliottProjectionSetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ElliottProjectionService {
    static final int REQUIRED_CANDLE_HISTORY = 260;
    private final ElliottProjectionSetRepository setRepository;
    private final ElliottProjectionScenarioRepository scenarioRepository;

    public ElliottProjectionService(
            ElliottProjectionSetRepository setRepository,
            ElliottProjectionScenarioRepository scenarioRepository) {
        this.setRepository = setRepository;
        this.scenarioRepository = scenarioRepository;
    }

    Optional<PreparedProjection> prepare(
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            long sourceTimestamp,
            double sourcePrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            List<Candle> candles,
            TimeInterval interval) {
        ElliottWaveDetectionService.ElliottWavePoint endpoint = points == null || points.isEmpty()
                ? null : points.getLast();
        long projectionTimestamp = endpoint != null && endpoint.timestamp() != null
                && endpoint.timestamp() > 0 ? endpoint.timestamp() : sourceTimestamp;
        double projectionPrice = endpoint != null && Double.isFinite(endpoint.price())
                && endpoint.price() > 0.0 ? endpoint.price() : sourcePrice;
        List<ElliottProjectionPolicy.Scenario> scenarios = ElliottProjectionPolicy.generate(
                stage, cycleDirection, expectedMove, projectionTimestamp, projectionPrice,
                points, candles, interval);
        return scenarios.isEmpty() ? Optional.empty()
                : Optional.of(new PreparedProjection(
                stage, projectionTimestamp, projectionPrice, scenarios));
    }

    @Transactional
    public boolean backfillIfMissing(
            AlertEvent event,
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            long sourceTimestamp,
            double sourcePrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            List<Candle> candles,
            TimeInterval interval) {
        if (event == null || event.getId() == null || stage == null
                || points == null || points.isEmpty()) return false;
        if (hasProjection(event, stage, points)) return false;
        Optional<PreparedProjection> prepared = prepare(
                stage, cycleDirection, expectedMove, sourceTimestamp, sourcePrice,
                points, candles, interval);
        if (prepared.isEmpty()) return false;
        persist(event, prepared.orElseThrow());
        return true;
    }

    @Transactional
    public ElliottProjectionSet persist(AlertEvent event, PreparedProjection prepared) {
        if (event == null || event.getId() == null || prepared == null) {
            throw new IllegalArgumentException("A persisted Elliott event and prepared projection are required.");
        }
        LocalDateTime now = LocalDateTime.now();
        List<ElliottProjectionSet> open = setRepository.findByAlertEventAndStatusInOrderByCreatedAtAscIdAsc(
                event, List.of(ElliottProjectionSetStatus.ACTIVE));
        for (ElliottProjectionSet previous : open) {
            boolean revised = previous.getStage() == prepared.stage();
            previous.setStatus(revised
                    ? ElliottProjectionSetStatus.SUPERSEDED : ElliottProjectionSetStatus.COMPLETED);
            previous.setResolutionTimestamp(prepared.sourceTimestamp());
            previous.setResolutionReason(revised
                    ? "The retained Elliott count revised this stage endpoint and replaced these paths."
                    : "The next validated Elliott stage ended the projected wave.");
            previous.setUpdatedAt(now);
            List<ElliottProjectionScenario> previousScenarios =
                    scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(previous);
            for (ElliottProjectionScenario scenario : previousScenarios) {
                if (!isOpen(scenario.getStatus())) continue;
                scenario.setStatus(revised
                        ? ElliottProjectionScenarioStatus.SUPERSEDED
                        : ElliottProjectionScenarioStatus.COMPLETED);
                scenario.setResolutionTimestamp(prepared.sourceTimestamp());
                scenario.setResolutionReason(previous.getResolutionReason());
                scenario.setUpdatedAt(now);
            }
            if (!previousScenarios.isEmpty()) scenarioRepository.saveAll(previousScenarios);
        }
        if (!open.isEmpty()) setRepository.saveAll(open);

        ElliottProjectionSet projectionSet = new ElliottProjectionSet();
        projectionSet.setAlertEvent(event);
        projectionSet.setStage(prepared.stage());
        projectionSet.setStageRevision((int) setRepository.countByAlertEventAndStage(
                event, prepared.stage()) + 1);
        projectionSet.setDevelopmentKey(event.getElliottDevelopmentKey());
        projectionSet.setSourceTimestamp(prepared.sourceTimestamp());
        projectionSet.setSourcePrice(prepared.sourcePrice());
        projectionSet.setStatus(ElliottProjectionSetStatus.ACTIVE);
        projectionSet.setLastEvaluatedTimestamp(prepared.sourceTimestamp());
        projectionSet.setEvaluatedCandleCount(0);
        projectionSet.setCreatedAt(now);
        projectionSet.setUpdatedAt(now);
        projectionSet = setRepository.save(projectionSet);

        List<ElliottProjectionScenario> scenarios = new ArrayList<>();
        for (ElliottProjectionPolicy.Scenario preparedScenario : prepared.scenarios()) {
            ElliottProjectionScenario scenario = new ElliottProjectionScenario();
            scenario.setProjectionSet(projectionSet);
            scenario.setScenarioKey(preparedScenario.key());
            scenario.setLabel(preparedScenario.label());
            scenario.setDescription(preparedScenario.description());
            scenario.setDisplayRank(preparedScenario.displayRank());
            scenario.setExpectedMove(preparedScenario.expectedMove());
            scenario.setStatus(ElliottProjectionScenarioStatus.ACTIVE);
            scenario.setInitialConfidence(preparedScenario.confidence());
            scenario.setCurrentConfidence(preparedScenario.confidence());
            scenario.setProjectedPath(serializePath(preparedScenario.path()));
            scenario.setTargetZoneLow(preparedScenario.targetZoneLow());
            scenario.setTargetZoneHigh(preparedScenario.targetZoneHigh());
            scenario.setTargetMidpoint(preparedScenario.targetMidpoint());
            scenario.setTargetBasis(preparedScenario.targetBasis());
            scenario.setMinimumCandles(preparedScenario.minimumCandles());
            scenario.setMaximumCandles(preparedScenario.maximumCandles());
            scenario.setHardInvalidationPrice(preparedScenario.hardInvalidationPrice());
            scenario.setHardInvalidationSide(name(preparedScenario.hardInvalidationSide()));
            scenario.setScenarioInvalidationPrice(preparedScenario.scenarioInvalidationPrice());
            scenario.setScenarioInvalidationSide(name(preparedScenario.scenarioInvalidationSide()));
            scenario.setEvidence(String.join("\n", preparedScenario.evidence()));
            scenario.setEvaluationNote("Awaiting the first completed candle after the projection.");
            scenario.setCreatedAt(now);
            scenario.setUpdatedAt(now);
            scenarios.add(scenario);
        }
        scenarioRepository.saveAll(scenarios);
        return projectionSet;
    }

    @Transactional
    public int evaluateOpenProjections(
            String symbol,
            TimeInterval interval,
            List<Candle> availableCandles) {
        JobLeaseGuard.requireOwnership();
        if (symbol == null || symbol.isBlank() || interval == null
                || availableCandles == null || availableCandles.isEmpty()) return 0;
        List<Candle> candles = availableCandles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null
                        && candle.getHighPrice() != null && candle.getLowPrice() != null
                        && candle.getClosePrice() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (candles.isEmpty()) return 0;
        int changedSets = 0;
        for (ElliottProjectionSet projectionSet : setRepository.findForEvaluation(
                symbol, interval, ElliottProjectionSetStatus.ACTIVE)) {
            List<Candle> completedAfterSource = candles.stream()
                    .filter(candle -> candle.getTimestamp() > projectionSet.getSourceTimestamp())
                    .toList();
            if (completedAfterSource.isEmpty()) continue;
            List<Candle> newCandles = completedAfterSource.stream()
                    .filter(candle -> projectionSet.getLastEvaluatedTimestamp() == null
                            || candle.getTimestamp() > projectionSet.getLastEvaluatedTimestamp())
                    .toList();
            if (newCandles.isEmpty()) continue;
            List<ElliottProjectionScenario> scenarios =
                    scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(projectionSet);
            boolean changed = false;
            Candle latest = completedAfterSource.getLast();
            int evaluatedCandleCount = projectionSet.getEvaluatedCandleCount() + newCandles.size();
            for (ElliottProjectionScenario scenario : scenarios) {
                if (!isOpen(scenario.getStatus())) continue;
                Evaluation evaluation = evaluate(
                        projectionSet, scenario, newCandles, evaluatedCandleCount, latest);
                if (evaluation == null) continue;
                scenario.setStatus(evaluation.status());
                scenario.setCurrentConfidence(evaluation.confidence());
                scenario.setEvaluationNote(evaluation.note());
                if (evaluation.status() == ElliottProjectionScenarioStatus.INVALIDATED) {
                    scenario.setResolutionTimestamp(evaluation.timestamp());
                    scenario.setResolutionReason(evaluation.note());
                }
                scenario.setUpdatedAt(LocalDateTime.now());
                changed = true;
            }
            if (!changed && projectionSet.getLastEvaluatedTimestamp() != null
                    && projectionSet.getLastEvaluatedTimestamp() >= latest.getTimestamp()) continue;

            rerank(scenarios);
            projectionSet.setLastEvaluatedTimestamp(latest.getTimestamp());
            projectionSet.setEvaluatedCandleCount(evaluatedCandleCount);
            if (scenarios.stream().noneMatch(scenario -> isOpen(scenario.getStatus()))) {
                projectionSet.setStatus(ElliottProjectionSetStatus.INVALIDATED);
                projectionSet.setResolutionTimestamp(latest.getTimestamp());
                projectionSet.setResolutionReason(
                        "Every conditional path was invalidated by completed price action.");
            }
            projectionSet.setUpdatedAt(LocalDateTime.now());
            scenarioRepository.saveAll(scenarios);
            setRepository.save(projectionSet);
            changedSets++;
        }
        return changedSets;
    }

    private Evaluation evaluate(
            ElliottProjectionSet projectionSet,
            ElliottProjectionScenario scenario,
            List<Candle> newCandles,
            int elapsed,
            Candle latest) {
        for (Candle candle : newCandles) {
            if (breached(candle, scenario.getHardInvalidationPrice(), scenario.getHardInvalidationSide())) {
                return new Evaluation(ElliottProjectionScenarioStatus.INVALIDATED, 0,
                        "A completed candle broke the Elliott count's hard structural boundary.",
                        candle.getTimestamp());
            }
            if (breached(candle, scenario.getScenarioInvalidationPrice(),
                    scenario.getScenarioInvalidationSide())) {
                return new Evaluation(ElliottProjectionScenarioStatus.INVALIDATED, 0,
                        "Price moved beyond this scenario's valid endpoint range while the wave remained open.",
                        candle.getTimestamp());
            }
        }

        double expectedDistance = scenario.getTargetMidpoint() - projectionSet.getSourcePrice();
        double progress = Math.abs(expectedDistance) <= .000001 ? 0.0
                : (latest.getClosePrice() - projectionSet.getSourcePrice()) / expectedDistance;
        double expectedProgress = Math.min(1.0, elapsed / (double) scenario.getMaximumCandles());
        int adjustment = (int) Math.round(Math.max(-15.0, Math.min(15.0,
                (progress - expectedProgress) * 18.0)));
        int confidence = Math.max(5, Math.min(85, scenario.getInitialConfidence() + adjustment));

        ElliottProjectionScenarioStatus status = scenario.getStatus();
        String note;
        if (elapsed > scenario.getMaximumCandles()) {
            status = ElliottProjectionScenarioStatus.DISFAVORED;
            confidence = Math.min(confidence, 24);
            note = "The wave remains open beyond this scenario's textbook candle window.";
        } else if (progress < -.20) {
            status = ElliottProjectionScenarioStatus.DISFAVORED;
            confidence = Math.min(confidence, 30);
            note = "Completed closes are moving materially away from this projected path.";
        } else if (elapsed < scenario.getMinimumCandles()) {
            note = "Developing normally; the earliest projected endpoint is candle "
                    + scenario.getMinimumCandles() + ".";
        } else if (progress >= .80) {
            note = "Price is approaching this target zone inside its projected candle window.";
        } else {
            note = "This scenario remains structurally valid after " + elapsed
                    + " completed candle" + (elapsed == 1 ? "." : "s.");
        }
        return new Evaluation(status, confidence, note, latest.getTimestamp());
    }

    private boolean breached(Candle candle, Double price, String side) {
        if (price == null || side == null || !Double.isFinite(price)) return false;
        return "BELOW".equals(side) ? candle.getLowPrice() <= price
                : candle.getHighPrice() >= price;
    }

    private void rerank(List<ElliottProjectionScenario> scenarios) {
        List<ElliottProjectionScenario> open = scenarios.stream()
                .filter(scenario -> isOpen(scenario.getStatus()))
                .sorted(Comparator.comparingInt(ElliottProjectionScenario::getCurrentConfidence).reversed()
                        .thenComparing(ElliottProjectionScenario::getScenarioKey))
                .toList();
        for (int index = 0; index < open.size(); index++) {
            open.get(index).setDisplayRank(index + 1);
        }
    }

    @Transactional(readOnly = true)
    public Optional<ProjectionSetView> latestVisible(Long eventId, User user) {
        if (eventId == null || user == null) return Optional.empty();
        return setRepository.findOwnedHistory(eventId, user).stream()
                .filter(set -> set.getStatus() == ElliottProjectionSetStatus.ACTIVE)
                .findFirst()
                .map(this::view);
    }

    boolean hasOpenProjections(String symbol, TimeInterval interval) {
        return symbol != null && !symbol.isBlank() && interval != null
                && setRepository.existsOpenProjection(symbol, interval);
    }

    boolean hasProjection(
            AlertEvent event,
            ElliottSignalStage stage,
            List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        if (event == null || event.getId() == null || stage == null
                || points == null || points.isEmpty() || points.getLast().timestamp() == null) {
            return false;
        }
        return setRepository.existsByAlertEventAndStageAndSourceTimestamp(
                event, stage, points.getLast().timestamp());
    }

    @Transactional(readOnly = true)
    public List<ProjectionSetView> history(Long eventId, User user) {
        if (eventId == null || user == null) return List.of();
        return setRepository.findOwnedHistory(eventId, user).stream().map(this::view).toList();
    }

    private ProjectionSetView view(ElliottProjectionSet set) {
        List<ElliottProjectionScenario> stored =
                scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(set);
        List<ScenarioView> visible = stored.stream()
                .filter(scenario -> isOpen(scenario.getStatus()))
                .sorted(Comparator.comparingInt(ElliottProjectionScenario::getDisplayRank))
                .map(this::view)
                .toList();
        return new ProjectionSetView(
                set.getStage(), set.getStageRevision(), set.getStatus(),
                set.getSourceTimestamp(), set.getSourcePrice(), set.getLastEvaluatedTimestamp(),
                set.getEvaluatedCandleCount(),
                visible, stored.size() - visible.size(), set.getResolutionTimestamp(),
                set.getResolutionReason());
    }

    private ScenarioView view(ElliottProjectionScenario scenario) {
        return new ScenarioView(
                scenario.getScenarioKey(), scenario.getLabel(), scenario.getDescription(),
                scenario.getDisplayRank(), scenario.getExpectedMove(), scenario.getStatus(),
                scenario.getCurrentConfidence(), parsePath(scenario.getProjectedPath()),
                scenario.getTargetZoneLow(), scenario.getTargetZoneHigh(),
                scenario.getTargetMidpoint(), scenario.getTargetBasis(),
                scenario.getMinimumCandles(), scenario.getMaximumCandles(),
                scenario.getHardInvalidationPrice(), scenario.getHardInvalidationSide(),
                scenario.getEvidence() == null || scenario.getEvidence().isBlank()
                        ? List.of() : scenario.getEvidence().lines().toList(),
                scenario.getEvaluationNote());
    }

    private static boolean isOpen(ElliottProjectionScenarioStatus status) {
        return status == ElliottProjectionScenarioStatus.ACTIVE
                || status == ElliottProjectionScenarioStatus.DISFAVORED;
    }

    private static String serializePath(List<ElliottProjectionPolicy.ProjectedPoint> path) {
        return path.stream()
                .map(point -> "%d|%d|%.10f|%s".formatted(
                        point.candleOffset(), point.timestamp(), point.price(), point.label()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static List<ProjectedPointView> parsePath(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return List.of();
        return snapshot.lines().map(line -> line.split("\\|", -1)).map(fields -> {
            if (fields.length != 4) return null;
            try {
                return new ProjectedPointView(Integer.parseInt(fields[0]), Long.parseLong(fields[1]),
                        Double.parseDouble(fields[2]), fields[3]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }

    private static String name(ElliottProjectionPolicy.BoundarySide side) {
        return side == null ? null : side.name();
    }

    record PreparedProjection(
            ElliottSignalStage stage,
            long sourceTimestamp,
            double sourcePrice,
            List<ElliottProjectionPolicy.Scenario> scenarios) { }

    private record Evaluation(
            ElliottProjectionScenarioStatus status,
            int confidence,
            String note,
            long timestamp) { }

    public record ProjectionSetView(
            ElliottSignalStage stage,
            int revision,
            ElliottProjectionSetStatus status,
            long sourceTimestamp,
            double sourcePrice,
            Long lastEvaluatedTimestamp,
            int evaluatedCandleCount,
            List<ScenarioView> scenarios,
            int hiddenScenarioCount,
            Long resolutionTimestamp,
            String resolutionReason) {
        public ProjectionSetView {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        }
    }

    public record ScenarioView(
            String key,
            String label,
            String description,
            int rank,
            TradeSignal expectedMove,
            ElliottProjectionScenarioStatus status,
            int confidence,
            List<ProjectedPointView> path,
            double targetZoneLow,
            double targetZoneHigh,
            double targetMidpoint,
            String targetBasis,
            int minimumCandles,
            int maximumCandles,
            Double hardInvalidationPrice,
            String hardInvalidationSide,
            List<String> evidence,
            String evaluationNote) {
        public ScenarioView {
            path = path == null ? List.of() : List.copyOf(path);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record ProjectedPointView(int candleOffset, long timestamp, double price, String label) { }
}
