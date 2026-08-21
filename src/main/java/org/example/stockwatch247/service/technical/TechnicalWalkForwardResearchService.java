package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.backtest.BacktestExecutor;
import org.ta4j.core.backtest.StrategyWalkForwardExecutionResult;
import org.ta4j.core.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.criteria.drawdown.MaximumDrawdownCriterion;
import org.ta4j.core.criteria.pnl.NetReturnCriterion;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.BooleanIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;
import org.ta4j.core.walkforward.WalkForwardConfig;

import java.util.List;

/**
 * Offline-only candidate evaluation. Nothing here is called by detection,
 * scoring, alerts, or request handling; results must be reviewed before an
 * indicator can be considered for a separately versioned production change.
 */
public final class TechnicalWalkForwardResearchService {
    private final Ta4jBarSeriesFactory seriesFactory = new Ta4jBarSeriesFactory();
    private final Ta4jIndicatorRegistry indicatorRegistry = new Ta4jIndicatorRegistry();

    public WalkForwardReport evaluate(List<Candle> candles,
                                      TechnicalIndicatorParameters parameters,
                                      Candidate candidate,
                                      WalkForwardConfig config) {
        BarSeries series = seriesFactory.create(candles);
        Ta4jIndicatorRegistry.ResearchIndicators indicators = indicatorRegistry.research(series, parameters);
        Strategy strategy = strategy(candidate, indicators, series);
        int unstable = unstableBars(candidate, indicators);
        strategy.setUnstableBars(unstable);
        WalkForwardConfig effectiveConfig = config == null
                ? WalkForwardConfig.defaultConfig(series) : config;
        StrategyWalkForwardExecutionResult result = new BacktestExecutor(series)
                .executeWalkForward(strategy, series.numFactory().one(), effectiveConfig);
        List<Num> returns = result.outOfSampleCriterionValues(new NetReturnCriterion());
        List<Num> drawdowns = result.outOfSampleCriterionValues(new MaximumDrawdownCriterion());
        List<Num> positions = result.outOfSampleCriterionValues(new NumberOfPositionsCriterion());
        return new WalkForwardReport(
                candidate,
                result.outOfSampleFolds().size(),
                average(returns),
                average(drawdowns),
                positions.stream().mapToInt(Num::intValue).sum(),
                effectiveConfig.configHash(),
                "Research only; no production detector, score, explanation, or alert was changed.");
    }

    private Strategy strategy(Candidate candidate,
                              Ta4jIndicatorRegistry.ResearchIndicators indicators,
                              BarSeries series) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        Rule entry;
        Rule exit;
        switch (candidate) {
            case DMI_ADX -> {
                entry = new OverIndicatorRule(indicators.plusDi(), indicators.minusDi())
                        .and(new OverIndicatorRule(indicators.adx(), 20));
                exit = new UnderIndicatorRule(indicators.plusDi(), indicators.minusDi());
            }
            case STOCHASTIC -> {
                entry = new UnderIndicatorRule(indicators.stochasticK(), 20);
                exit = new OverIndicatorRule(indicators.stochasticK(), 80);
            }
            case STOCHASTIC_RSI -> {
                entry = new UnderIndicatorRule(indicators.stochasticRsi(), 20);
                exit = new OverIndicatorRule(indicators.stochasticRsi(), 80);
            }
            case MONEY_FLOW -> {
                entry = new UnderIndicatorRule(indicators.moneyFlow(), 20);
                exit = new OverIndicatorRule(indicators.moneyFlow(), 80);
            }
            case DONCHIAN -> {
                entry = new UnderIndicatorRule(close, indicators.donchianMiddle());
                exit = new OverIndicatorRule(close, indicators.donchianMiddle());
            }
            case KELTNER -> {
                entry = new OverIndicatorRule(close, indicators.keltnerUpper());
                exit = new UnderIndicatorRule(close, indicators.keltnerMiddle());
            }
            case TA4J_TREND -> {
                entry = new BooleanIndicatorRule(indicators.upTrend());
                exit = new BooleanIndicatorRule(indicators.downTrend());
            }
            default -> throw new IllegalArgumentException("Unsupported research candidate: " + candidate);
        }
        return new BaseStrategy("research-" + candidate.name().toLowerCase(), entry, exit);
    }

    private int unstableBars(Candidate candidate,
                             Ta4jIndicatorRegistry.ResearchIndicators indicators) {
        return switch (candidate) {
            case DMI_ADX -> maxUnstable(indicators.adx(), indicators.plusDi(), indicators.minusDi());
            case STOCHASTIC -> indicators.stochasticK().getCountOfUnstableBars();
            case STOCHASTIC_RSI -> indicators.stochasticRsi().getCountOfUnstableBars();
            case MONEY_FLOW -> indicators.moneyFlow().getCountOfUnstableBars();
            case DONCHIAN -> maxUnstable(indicators.donchianLower(), indicators.donchianMiddle());
            case KELTNER -> maxUnstable(indicators.keltnerMiddle(), indicators.keltnerUpper());
            case TA4J_TREND -> maxUnstable(indicators.upTrend(), indicators.downTrend());
        };
    }

    private int maxUnstable(Indicator<?>... indicators) {
        int maximum = 0;
        for (Indicator<?> indicator : indicators) {
            maximum = Math.max(maximum, indicator.getCountOfUnstableBars());
        }
        return maximum;
    }

    private double average(List<Num> values) {
        return values.stream().mapToDouble(Num::doubleValue).average().orElse(Double.NaN);
    }

    public enum Candidate {
        DMI_ADX,
        STOCHASTIC,
        STOCHASTIC_RSI,
        MONEY_FLOW,
        DONCHIAN,
        KELTNER,
        TA4J_TREND
    }

    public record WalkForwardReport(Candidate candidate,
                                    int outOfSampleFolds,
                                    double averageNetReturn,
                                    double averageMaximumDrawdown,
                                    int positions,
                                    String configurationHash,
                                    String boundaryNotice) {
    }
}
