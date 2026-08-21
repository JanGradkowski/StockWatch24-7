package org.example.stockwatch247.service.technical;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.CCIIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.StochasticRSIIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.adx.MinusDIIndicator;
import org.ta4j.core.indicators.adx.PlusDIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelFacade;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.keltner.KeltnerChannelFacade;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.supportresistance.VolumeProfileKDEIndicator;
import org.ta4j.core.indicators.trend.DownTrendIndicator;
import org.ta4j.core.indicators.trend.UpTrendIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.Num;

/**
 * The single construction point for TA4J indicators. Core indicators preserve
 * the established production contract; research indicators are deliberately
 * kept in a separate bundle and never participate in signal scoring.
 */
public final class Ta4jIndicatorRegistry {

    public CoreIndicators core(BarSeries series, TechnicalIndicatorParameters parameters) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator averageVolume = new SMAIndicator(volume, parameters.volumePeriod());
        RSIIndicator rsi = new RSIIndicator(close, parameters.rsiPeriod());
        EMAIndicator fastEma = new EMAIndicator(close, parameters.fastEmaPeriod());
        EMAIndicator slowEma = new EMAIndicator(close, parameters.slowEmaPeriod());
        SMAIndicator longSma = new SMAIndicator(close, parameters.longSmaPeriod());
        MACDIndicator macd = new MACDIndicator(
                close, parameters.macdFastPeriod(), parameters.macdSlowPeriod());
        Indicator<Num> macdSignal = macd.getSignalLine(parameters.macdSignalPeriod());
        Indicator<Num> macdHistogram = macd.getHistogram(parameters.macdSignalPeriod());
        CCIIndicator cci = new CCIIndicator(series, parameters.cciPeriod());
        ATRIndicator atr = new ATRIndicator(series, parameters.atrPeriod());
        BollingerBandsMiddleIndicator bollingerMiddle = new BollingerBandsMiddleIndicator(
                new SMAIndicator(close, parameters.bollingerPeriod()));
        StandardDeviationIndicator deviation = StandardDeviationIndicator.ofPopulation(
                close, parameters.bollingerPeriod());
        Num multiplier = series.numFactory().numOf(parameters.bollingerDeviation());
        BollingerBandsLowerIndicator bollingerLower = new BollingerBandsLowerIndicator(
                bollingerMiddle, deviation, multiplier);
        BollingerBandsUpperIndicator bollingerUpper = new BollingerBandsUpperIndicator(
                bollingerMiddle, deviation, multiplier);
        VWAPIndicator rollingVwap = new VWAPIndicator(
                new TypicalPriceIndicator(series), volume, parameters.vwapPeriod());
        return new CoreIndicators(
                averageVolume, rsi, fastEma, slowEma, longSma, macd, macdSignal,
                macdHistogram, cci, bollingerMiddle, bollingerLower, bollingerUpper,
                atr, rollingVwap);
    }

    public ResearchIndicators research(BarSeries series, TechnicalIndicatorParameters parameters) {
        ADXIndicator adx = new ADXIndicator(series, parameters.adxPeriod());
        PlusDIIndicator plusDi = new PlusDIIndicator(series, parameters.adxPeriod());
        MinusDIIndicator minusDi = new MinusDIIndicator(series, parameters.adxPeriod());
        StochasticOscillatorKIndicator stochasticK = new StochasticOscillatorKIndicator(
                series, parameters.stochasticPeriod());
        StochasticOscillatorDIndicator stochasticD = new StochasticOscillatorDIndicator(stochasticK);
        StochasticRSIIndicator stochasticRsi = new StochasticRSIIndicator(
                series, parameters.stochasticPeriod());
        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);
        MoneyFlowIndexIndicator moneyFlow = new MoneyFlowIndexIndicator(
                series, parameters.moneyFlowPeriod());
        DonchianChannelFacade donchian = new DonchianChannelFacade(series, parameters.channelPeriod());
        KeltnerChannelFacade keltner = new KeltnerChannelFacade(
                series, parameters.channelPeriod(), parameters.atrPeriod(),
                parameters.bollingerDeviation());
        UpTrendIndicator upTrend = new UpTrendIndicator(series, parameters.trendPeriod());
        DownTrendIndicator downTrend = new DownTrendIndicator(series, parameters.trendPeriod());
        TypicalPriceIndicator typicalPrice = new TypicalPriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        VolumeProfileKDEIndicator volumeProfileKde = new VolumeProfileKDEIndicator(
                typicalPrice,
                volume,
                parameters.volumeProfilePeriod(),
                series.numFactory().numOf(causalBandwidth(series)));
        return new ResearchIndicators(
                adx, plusDi, minusDi, stochasticK, stochasticD, stochasticRsi,
                obv, moneyFlow, donchian.lower(), donchian.middle(), donchian.upper(),
                keltner.lower(), keltner.middle(), keltner.upper(), upTrend, downTrend,
                volumeProfileKde);
    }

    private double causalBandwidth(BarSeries series) {
        Bar first = series.getFirstBar();
        double close = Math.abs(first.getClosePrice().doubleValue());
        double range = Math.abs(first.getHighPrice().minus(first.getLowPrice()).doubleValue());
        return Math.max(0.000_001, Math.max(close * 0.0025, range * 0.5));
    }

    public record CoreIndicators(
            Indicator<Num> averageVolume,
            Indicator<Num> rsi,
            Indicator<Num> fastEma,
            Indicator<Num> slowEma,
            Indicator<Num> longSma,
            Indicator<Num> macd,
            Indicator<Num> macdSignal,
            Indicator<Num> macdHistogram,
            Indicator<Num> cci,
            Indicator<Num> bollingerMiddle,
            Indicator<Num> bollingerLower,
            Indicator<Num> bollingerUpper,
            Indicator<Num> atr,
            Indicator<Num> rollingVwap) {

        public int stableBars(Indicator<?> indicator, int establishedMinimumBars) {
            return Math.max(establishedMinimumBars, indicator.getCountOfUnstableBars());
        }
    }

    public record ResearchIndicators(
            Indicator<Num> adx,
            Indicator<Num> plusDi,
            Indicator<Num> minusDi,
            Indicator<Num> stochasticK,
            Indicator<Num> stochasticD,
            Indicator<Num> stochasticRsi,
            Indicator<Num> obv,
            Indicator<Num> moneyFlow,
            Indicator<Num> donchianLower,
            Indicator<Num> donchianMiddle,
            Indicator<Num> donchianUpper,
            Indicator<Num> keltnerLower,
            Indicator<Num> keltnerMiddle,
            Indicator<Num> keltnerUpper,
            Indicator<Boolean> upTrend,
            Indicator<Boolean> downTrend,
            VolumeProfileKDEIndicator volumeProfileKde) {
    }
}
