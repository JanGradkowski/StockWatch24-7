package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.ElliottProjectionScenario;
import org.example.stockwatch247.model.ElliottProjectionSet;
import org.example.stockwatch247.model.ElliottProjectionRevision;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottProjectionScenarioStatus;
import org.example.stockwatch247.model.enums.ElliottProjectionSetStatus;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.ElliottProjectionScenarioRepository;
import org.example.stockwatch247.repository.ElliottProjectionSetRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.TreeMap;

@Service
public class ElliottProjectionService {
    static final int REQUIRED_CANDLE_HISTORY = 260;
    private final ElliottProjectionSetRepository setRepository;
    private final ElliottProjectionScenarioRepository scenarioRepository;
    private final CandleCompletionService candleCompletionService;

    public ElliottProjectionService(
            ElliottProjectionSetRepository setRepository,
            ElliottProjectionScenarioRepository scenarioRepository) {
        this(setRepository, scenarioRepository, new CandleCompletionService("Europe/Brussels"));
    }

    @Autowired
    public ElliottProjectionService(ElliottProjectionSetRepository setRepository,
                                    ElliottProjectionScenarioRepository scenarioRepository,
                                    CandleCompletionService candleCompletionService) {
        this.setRepository = setRepository;
        this.scenarioRepository = scenarioRepository;
        this.candleCompletionService = candleCompletionService;
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
                stage, projectionTimestamp, projectionPrice, scenarios,
                Math.max(sourceTimestamp, projectionTimestamp)));
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
            previous.setResolutionTimestamp(prepared.availableFromTimestamp());
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
                scenario.setResolutionTimestamp(prepared.availableFromTimestamp());
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
        projectionSet.setAvailableFromTimestamp(prepared.availableFromTimestamp());
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
            scenario.setOriginalMaximumCandles(preparedScenario.maximumCandles());
            scenario.setTargetHistoryChecked(true);
            scenario.setHardInvalidationPrice(preparedScenario.hardInvalidationPrice());
            scenario.setHardInvalidationSide(name(preparedScenario.hardInvalidationSide()));
            scenario.setScenarioInvalidationPrice(preparedScenario.scenarioInvalidationPrice());
            scenario.setScenarioInvalidationSide(name(preparedScenario.scenarioInvalidationSide()));
            scenario.setEvidence(String.join("\n", preparedScenario.evidence()));
            scenario.setEvaluationNote("Awaiting the first completed candle after the projection.");
            scenario.setCreatedAt(now);
            scenario.setUpdatedAt(now);
            scenario.getRevisions().add(new ElliottProjectionRevision(prepared.availableFromTimestamp(), scenario,
                    "Initial conditional projection from the validated stage endpoint."));
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
        if (interval != TimeInterval.DAILY && interval != TimeInterval.WEEKLY
                && interval != TimeInterval.MONTHLY) return 0;
        long incomplete = candleCompletionService.firstIncompleteCandleTimestamp(interval);
        // Duplicate provider rows must not count as independent evidence.
        TreeMap<Long, Candle> unique = new TreeMap<>();
        for (Candle candle : availableCandles) {
            if (TradeRiskPolicy.valid(candle) && candle.getTimestamp() != null
                    && candle.getTimestamp() < incomplete) unique.put(candle.getTimestamp(), candle);
        }
        List<Candle> candles = List.copyOf(unique.values());
        if (candles.isEmpty()) return 0;
        int changedSets = 0;
        for (ElliottProjectionSet set : setRepository.findForEvaluation(
                symbol, interval, ElliottProjectionSetStatus.ACTIVE)) {
            if (set.getStatus() != ElliottProjectionSetStatus.ACTIVE) continue;
            List<ElliottProjectionScenario> scenarios =
                    scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(set);
            boolean changed = false;
            for (int index = 0; index < candles.size(); index++) {
                Candle candle = candles.get(index);
                if (candle.getTimestamp() <= set.getSourceTimestamp()
                        || set.getLastEvaluatedTimestamp() != null
                        && candle.getTimestamp() <= set.getLastEvaluatedTimestamp()) continue;
                int elapsed = set.getEvaluatedCandleCount() + 1;
                List<Candle> prefix = candles.subList(0, index + 1);
                // Age includes the confirmation delay, but no decision can predate knowledge of the count.
                if (candle.getTimestamp() >= set.getAvailableFromTimestamp()) {
                    for (ElliottProjectionScenario scenario : scenarios) {
                        if (isOpen(scenario.getStatus())) evaluate(set, scenario, prefix, elapsed, interval);
                    }
                    rerank(scenarios);
                }
                set.setLastEvaluatedTimestamp(candle.getTimestamp());
                set.setEvaluatedCandleCount(elapsed);
                changed = true;
                if (scenarios.stream().noneMatch(scenario -> isOpen(scenario.getStatus()))) {
                    set.setStatus(ElliottProjectionSetStatus.INVALIDATED);
                    set.setResolutionTimestamp(candle.getTimestamp());
                    set.setResolutionReason("Every conditional path was invalidated by completed price action.");
                    break;
                }
            }
            if (!changed) continue;
            set.setUpdatedAt(LocalDateTime.now());
            scenarioRepository.saveAll(scenarios);
            setRepository.save(set);
            changedSets++;
        }
        return changedSets;
    }

    private void evaluate(ElliottProjectionSet set, ElliottProjectionScenario scenario,
                          List<Candle> history, int elapsed, TimeInterval interval) {
        Candle latest = history.getLast();
        if (scenario.getOriginalMaximumCandles() <= 0) {
            scenario.setOriginalMaximumCandles(scenario.getMaximumCandles());
        }
        if (scenario.getRevisions().isEmpty() && scenario.getProjectedPath() != null) {
            scenario.getRevisions().add(new ElliottProjectionRevision(
                    Math.max(set.getSourceTimestamp(), set.getAvailableFromTimestamp()), scenario,
                    "Original stored projection, retained before adaptive monitoring."));
        }
        scenario.setUpdatedAt(LocalDateTime.now());
        // Hard wave rules use the full completed candle, not a delayed close-only vote.
        if (breached(latest, scenario.getHardInvalidationPrice(), scenario.getHardInvalidationSide())) {
            invalidate(scenario, latest, "A completed candle broke the Elliott count's hard structural boundary.");
            return;
        }
        double atr = ElliottProjectionAdaptationPolicy.recentAtr(history);
        double buffer = Double.isFinite(atr) ? atr * .5 : 0;
        double boundaryClose = latest.getClosePrice()
                + ("ABOVE".equals(scenario.getScenarioInvalidationSide()) ? -buffer : buffer);
        boolean beyondEndpoint = ElliottProjectionAdaptationPolicy.beyond(boundaryClose,
                scenario.getScenarioInvalidationPrice(), scenario.getScenarioInvalidationSide());
        scenario.setBoundaryStreak(beyondEndpoint ? scenario.getBoundaryStreak() + 1 : 0);

        // Existing rows may have passed their target before adaptive monitoring was deployed.
        // Recover the contact from available history without pretending a redraw occurred in the past.
        boolean recoveredContact = false;
        if (!scenario.isTargetHistoryChecked()) {
            if (scenario.getTargetReachedTimestamp() == null) {
                Long contact = history.stream().filter(c -> c.getTimestamp() > set.getSourceTimestamp()
                                && c.getTimestamp() >= set.getAvailableFromTimestamp() && touched(c, scenario))
                        .map(Candle::getTimestamp).findFirst().orElse(null);
                scenario.setTargetReachedTimestamp(contact);
                recoveredContact = contact != null;
            }
            scenario.setTargetHistoryChecked(true);
        }
        boolean targetTouched = touched(latest, scenario);
        if (scenario.getTargetReachedTimestamp() == null && targetTouched) {
            scenario.setTargetReachedTimestamp(latest.getTimestamp());
        }
        if (scenario.getBoundaryStreak() >= ElliottProjectionAdaptationPolicy.REQUIRED_CLOSES) {
            invalidate(scenario, latest,
                    "Three completed closes exceeded this scenario's endpoint range; other counts remain independently evaluated.");
            return;
        }
        var adaptation = ElliottProjectionAdaptationPolicy.assess(set, scenario, history, elapsed, interval);
        scenario.setDeviationKind(adaptation.deviation());
        scenario.setDeviationStreak(adaptation.streak());
        boolean supportedReturn = !targetTouched && adaptation.revised() && adaptation.structuralSupport() > 0
                && ElliottProjectionAdaptationPolicy.confirmedSwings(history, set.getSourceTimestamp()).stream()
                .anyMatch(swing -> scenario.getTargetReachedTimestamp() != null
                        && swing.timestamp() > scenario.getTargetReachedTimestamp());
        if (targetTouched || recoveredContact || scenario.getStatus() == ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION
                && !supportedReturn) {
            scenario.setStatus(ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION);
            scenario.setCurrentConfidence(Math.min(30, scenario.getInitialConfidence()));
            scenario.setEvaluationNote("Target zone touched; awaiting a validated wave endpoint or a supported extension. Target contact alone does not complete the wave.");
            if (targetTouched) {
                scenario.setDeviationKind(null);
                scenario.setDeviationStreak(0);
            }
            return;
        }
        double distance = scenario.getTargetMidpoint() - set.getSourcePrice();
        double progress = Math.abs(distance) < .000001 ? 0 : (latest.getClosePrice() - set.getSourcePrice()) / distance;
        double expectedProgress = Math.min(1.0, elapsed / (double) Math.max(1, scenario.getMaximumCandles()));
        int adjustment = (int) Math.round(Math.clamp((progress - expectedProgress) * 18.0, -15.0, 15.0));
        int confidence = Math.clamp(scenario.getInitialConfidence() + adjustment + adaptation.structuralSupport(), 5, 85);
        boolean unresolved = scenario.getStatus() == ElliottProjectionScenarioStatus.UNRESOLVED;
        scenario.setStatus(unresolved ? ElliottProjectionScenarioStatus.UNRESOLVED : ElliottProjectionScenarioStatus.ACTIVE);
        if (adaptation.revised()) {
            scenario.setProjectedPath(serializePath(adaptation.path()));
            scenario.setMinimumCandles(adaptation.minimumCandles());
            scenario.setMaximumCandles(adaptation.maximumCandles());
            scenario.setLastRevisionCandleCount(elapsed);
            scenario.setDeviationKind(null);
            scenario.setDeviationStreak(0);
            scenario.setStatus(ElliottProjectionScenarioStatus.ACTIVE);
            scenario.setEvaluationNote(adaptation.reason());
            scenario.getRevisions().add(new ElliottProjectionRevision(latest.getTimestamp(), scenario, adaptation.reason()));
        } else if (adaptation.unresolved() || unresolved) {
            scenario.setStatus(ElliottProjectionScenarioStatus.UNRESOLVED);
            confidence = Math.min(confidence, 20);
            scenario.setEvaluationNote(adaptation.unresolved() ? adaptation.reason()
                    : "No supported remaining path; awaiting sufficient evidence or the next validated stage.");
        } else if (elapsed > scenario.getMaximumCandles() || progress < -.20 || adaptation.structuralSupport() < 0) {
            scenario.setStatus(ElliottProjectionScenarioStatus.DISFAVORED);
            confidence = Math.min(confidence, 24);
            scenario.setEvaluationNote("The original path is under review; sustained completed-candle evidence is required before replacement.");
        } else {
            scenario.setEvaluationNote("Current path retained after " + elapsed + " completed candles; no decisive revision evidence.");
        }
        scenario.setCurrentConfidence(confidence);
    }

    private void invalidate(ElliottProjectionScenario scenario, Candle candle, String reason) {
        scenario.setStatus(ElliottProjectionScenarioStatus.INVALIDATED);
        scenario.setCurrentConfidence(0);
        scenario.setResolutionTimestamp(candle.getTimestamp());
        scenario.setResolutionReason(reason);
        scenario.setEvaluationNote(reason);
    }

    private boolean breached(Candle candle, Double price, String side) {
        return ElliottProjectionAdaptationPolicy.beyond("ABOVE".equals(side)
                ? candle.getHighPrice() : candle.getLowPrice(), price, side);
    }

    private static boolean drawable(ElliottProjectionScenario scenario) {
        return scenario.getStatus() == ElliottProjectionScenarioStatus.ACTIVE
                || scenario.getStatus() == ElliottProjectionScenarioStatus.DISFAVORED;
    }

    private void rerank(List<ElliottProjectionScenario> scenarios) {
        List<ElliottProjectionScenario> open = new ArrayList<>(scenarios.stream()
                .filter(scenario -> isOpen(scenario.getStatus()))
                .sorted(Comparator.comparingInt(ElliottProjectionScenario::getDisplayRank)
                        .thenComparing(ElliottProjectionScenario::getScenarioKey)).toList());
        if (open.isEmpty()) return;
        ElliottProjectionScenario incumbent = open.getFirst();
        var bySupport = Comparator.comparingInt((ElliottProjectionScenario s) -> drawable(s) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(ElliottProjectionScenario::getCurrentConfidence).reversed())
                .thenComparing(ElliottProjectionScenario::getScenarioKey);
        ElliottProjectionScenario best = open.stream().min(bySupport).orElseThrow();
        for (ElliottProjectionScenario scenario : open) {
            boolean challenger = scenario == best && scenario != incumbent && drawable(scenario)
                    && scenario.getCurrentConfidence() >= incumbent.getCurrentConfidence()
                    + ElliottProjectionAdaptationPolicy.PROMOTION_MARGIN;
            scenario.setPromotionStreak(challenger ? scenario.getPromotionStreak() + 1 : 0);
        }
        ElliottProjectionScenario preferred = !drawable(incumbent) && drawable(best)
                || best.getPromotionStreak() >= ElliottProjectionAdaptationPolicy.REQUIRED_CLOSES ? best : incumbent;
        open.sort(bySupport);
        open.remove(preferred);
        open.addFirst(preferred);
        for (int i = 0; i < open.size(); i++) open.get(i).setDisplayRank(i + 1);
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
        return setRepository.findOwnedHistory(eventId, user).stream().map(set -> view(set, true)).toList();
    }

    private ProjectionSetView view(ElliottProjectionSet set) {
        return view(set, false);
    }

    private boolean touched(Candle candle, ElliottProjectionScenario scenario) {
        return scenario.getExpectedMove() == TradeSignal.BUY
                ? candle.getHighPrice() >= scenario.getTargetZoneLow()
                : candle.getLowPrice() <= scenario.getTargetZoneHigh();
    }

    private ProjectionSetView view(ElliottProjectionSet set, boolean includeArchived) {
        List<ElliottProjectionScenario> stored =
                scenarioRepository.findByProjectionSetOrderByDisplayRankAscIdAsc(set);
        List<ScenarioView> visible = stored.stream()
                .filter(scenario -> includeArchived || isOpen(scenario.getStatus()))
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
                scenario.getScenarioKey(), scenario.getLabel() == null ? scenario.getScenarioKey()
                        : scenario.getLabel().replaceFirst("^(Preferred|Alternate [AB])\\s*·\\s*", ""), scenario.getDescription(),
                scenario.getDisplayRank(), scenario.getExpectedMove(), scenario.getStatus(),
                scenario.getCurrentConfidence(), parsePath(scenario.getProjectedPath()),
                scenario.getTargetZoneLow(), scenario.getTargetZoneHigh(),
                scenario.getTargetMidpoint(), scenario.getTargetBasis(),
                scenario.getMinimumCandles(), scenario.getMaximumCandles(),
                scenario.getHardInvalidationPrice(), scenario.getHardInvalidationSide(),
                scenario.getEvidence() == null || scenario.getEvidence().isBlank()
                        ? List.of() : scenario.getEvidence().lines().toList(),
                scenario.getEvaluationNote(), drawable(scenario), scenario.getTargetReachedTimestamp(),
                scenario.getRevisions().stream().map(revision -> new RevisionView(
                        revision.getDecisionTimestamp(), revision.getReason(),
                        revision.getMinimumCandles(), revision.getMaximumCandles(),
                        revision.getTargetZoneLow(), revision.getTargetZoneHigh(),
                        parsePath(revision.getProjectedPath()))).toList());
    }

    private static boolean isOpen(ElliottProjectionScenarioStatus status) {
        return status == ElliottProjectionScenarioStatus.ACTIVE
                || status == ElliottProjectionScenarioStatus.DISFAVORED
                || status == ElliottProjectionScenarioStatus.AWAITING_CONFIRMATION
                || status == ElliottProjectionScenarioStatus.UNRESOLVED;
    }

    private static String serializePath(List<ElliottProjectionPolicy.ProjectedPoint> path) {
        return path.stream()
                .map(point -> String.format(Locale.ROOT, "%d|%d|%.10f|%s",
                        point.candleOffset(), point.timestamp(), point.price(), point.label()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    static List<ProjectedPointView> parsePath(String snapshot) {
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
            List<ElliottProjectionPolicy.Scenario> scenarios,
            long availableFromTimestamp) {
        PreparedProjection(ElliottSignalStage stage, long sourceTimestamp, double sourcePrice,
                           List<ElliottProjectionPolicy.Scenario> scenarios) {
            this(stage, sourceTimestamp, sourcePrice, scenarios, sourceTimestamp);
        }
    }

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
            String evaluationNote,
            boolean drawable,
            Long targetReachedTimestamp,
            List<RevisionView> revisions) {
        public ScenarioView {
            path = path == null ? List.of() : List.copyOf(path);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            revisions = revisions == null ? List.of() : List.copyOf(revisions);
        }
    }

    public record RevisionView(long decisionTimestamp, String reason, int minimumCandles, int maximumCandles,
                               double targetZoneLow, double targetZoneHigh, List<ProjectedPointView> path) {
        public String decisionDate() { return date(decisionTimestamp); }
        public List<ProjectedPointView> turns() {
            return path.stream().filter(p -> !p.label().isBlank() || p == path.getFirst() || p == path.getLast()).toList();
        }
    }

    private static String date(long timestamp) {
        return java.time.Instant.ofEpochSecond(timestamp).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    public record ProjectedPointView(int candleOffset, long timestamp, double price, String label) {
        public String date() { return ElliottProjectionService.date(timestamp); }
    }
}
