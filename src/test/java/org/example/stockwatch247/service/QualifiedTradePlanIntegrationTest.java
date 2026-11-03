package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.*;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"alerts.schedule.enabled=false","email-outbox.worker-enabled=false","watchlists.imports.worker-enabled=false"})
@AutoConfigureMockMvc
@Transactional
class QualifiedTradePlanIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired AlertRuleRepository rules;
    @Autowired AlertEventRepository events;
    @Autowired CandleRepository candles;
    @Autowired SignalArchiveQuery query;
    @Autowired AlertRuleService archives;
    @Autowired MockMvc mvc;
    @Autowired ElliottTradePlanService elliott;
    @Test void newPlansPersistAndRenderedDetailsShowQualificationAndTargets() throws Exception {
        var u=new User();u.setEmail("trade-audit-"+UUID.randomUUID()+"@example.com");
        u.setFirstName("Trade");u.setLastName("Audit");u.setPasswordHash("test-only");u.setVerified(true);users.saveAndFlush(u);
        var a=new StockAsset();a.setTickerSymbol("QA"+UUID.randomUUID().toString().substring(0,8));a.setCompanyName("Trade audit");a.setExchange("NASDAQ");assets.saveAndFlush(a);
        for(int i=0;i<30;i++) candles.save(new Candle(a.getTickerSymbol(),"1d",1704067200L+i*86400L,100,103,97,100.0,100L));
        candles.flush();
        for(var family:List.of(AlertPatternFamily.CANDLESTICK,AlertPatternFamily.HARMONIC_FORMATION,AlertPatternFamily.ELLIOTT_WAVE)) {
            var r=new AlertRule();r.setUser(u);r.setStockAsset(a);r.setInterval(TimeInterval.DAILY);r.setPatternFamily(family);rules.saveAndFlush(r);
            var e=new AlertEvent();e.setAlertRule(r);e.setPattern(family==AlertPatternFamily.HARMONIC_FORMATION?CandlePattern.HARMONIC_BAT:family==AlertPatternFamily.ELLIOTT_WAVE?CandlePattern.ELLIOTT_BULLISH_WAVE_II_END:CandlePattern.BULLISH_ENGULFING);
            e.setTradeSignal(TradeSignal.BUY);e.setSignalCandleTimestamp(1705795200L);e.setClosePrice(100.0);e.setConfidenceScore(80);
            e.setLifecycleStatus(SignalLifecycleStatus.DETECTED);
            e.setTradeEntryPrice(100.0);e.setStopLossPrice(94.8);e.setStructuralStopPrice(95.0);e.setProfitTargetPrice(110.4);e.setRewardRiskRatio(2.0);
            e.setTradePlanVersion(family==AlertPatternFamily.CANDLESTICK?"CANDLE_RR_V4":family==AlertPatternFamily.HARMONIC_FORMATION?"HARMONIC_TRADE_V2":"ELLIOTT_FIB_RR_V3");
            e.setTradeActionable(true);e.setTradeQualification("Qualified against the primary target and interval risk limits.");e.setTradeRiskPercent(5.2);e.setTradeRiskAtr(1.3);e.setSecondaryTargetPrice(115.0);e.setTradeHorizonCandles(12);
            e.setConfirmationWindowCandles(12);e.setConfirmationTriggerPrice(110.4);e.setInvalidationPrice(95.0);e.setDetectionCandleTimestamp(e.getSignalCandleTimestamp());e.setDetectionClosePrice(100.0);
            if(family==AlertPatternFamily.HARMONIC_FORMATION) {e.setHarmonicStopStatus("ACTIVE");e.setHarmonicStopBasis("Point X");e.setHarmonicStopFormula("Point X");e.setHarmonicEndpointPrice(97.0);e.setHarmonicStopBufferAmount(.2);e.setHarmonicStopBufferPercent(.21);e.setHarmonicStopDistancePercent(5.2);}
            if(family==AlertPatternFamily.ELLIOTT_WAVE) {e.setElliottSignalStage(ElliottSignalStage.WAVE_II_END);e.setElliottTargetZoneLow(110.4);e.setElliottTargetZoneHigh(111.4);e.setElliottTargetMidPrice(110.9);e.setElliottTargetBasis("Wave III");e.setElliottTradeActionable(true);e.setElliottRequiredRewardRiskRatio(2.0);e.setElliottTradePlanStatus("ACTIVE");}
            events.saveAndFlush(e);
            String html=mvc.perform(get("/alerts/signals/"+e.getId()).with(user(u.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,u.getSecurityVersion()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Qualified trade plan","Secondary scenario").containsIgnoringCase("protective");
            if(family==AlertPatternFamily.HARMONIC_FORMATION) assertThat(html).contains("Primary target");
            e.setTradeActionable(false);e.setTradeQualification("Projection only: insufficient valid volatility history.");
            e.setTradeRiskAtr(null);
            if(family==AlertPatternFamily.HARMONIC_FORMATION) e.setHarmonicStopStatus("PROJECTION_ONLY");
            if(family==AlertPatternFamily.ELLIOTT_WAVE) {e.setElliottTradeActionable(false);e.setElliottTradePlanStatus("PROJECTION_ONLY");}
            events.saveAndFlush(e);
            String projection=mvc.perform(get("/alerts/signals/"+e.getId()).with(user(u.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,u.getSecurityVersion()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(projection).contains("Projection only", "insufficient valid volatility history");

        }
        assertThat(query.page(u.getId(),null,false,0,50).count()).isEqualTo(3);
    }
    @Test void persistedGapFillControlsArchiveReturnAndProjectionShowsItsModeledReturn() {
        var u=new User();u.setEmail("gap-"+UUID.randomUUID()+"@example.com");u.setFirstName("Gap");u.setLastName("Test");u.setPasswordHash("test");u.setVerified(true);users.saveAndFlush(u);
        var a=new StockAsset();a.setTickerSymbol("QG"+UUID.randomUUID().toString().substring(0,8));a.setCompanyName("Gap");a.setExchange("NASDAQ");assets.saveAndFlush(a);
        var r=new AlertRule();r.setUser(u);r.setStockAsset(a);r.setInterval(TimeInterval.DAILY);r.setPatternFamily(AlertPatternFamily.CANDLESTICK);rules.saveAndFlush(r);
        var e=new AlertEvent();e.setAlertRule(r);e.setPattern(CandlePattern.BULLISH_ENGULFING);e.setTradeSignal(TradeSignal.BUY);e.setClosePrice(100.0);e.setSignalCandleTimestamp(1704067200L);
        e.setTradePlanVersion("CANDLE_RR_V4");e.setTradeEntryPrice(100.0);e.setStructuralStopPrice(95.0);e.setStopLossPrice(94.8);e.setProfitTargetPrice(110.4);e.setRewardRiskRatio(2.0);e.setTradeActionable(true);e.setTradeResolutionPrice(90.0);e.setLifecycleStatus(SignalLifecycleStatus.INVALIDATED);events.saveAndFlush(e);
        var output=archives.getSignalArchive(u,"trade-return","desc",0).signals().getFirst();
        assertThat(output.outcome().returnPercent()).isEqualTo(-10);
        assertThat(output.outcome().price()).isEqualTo(90);
        e.setTradeActionable(false);e.setTradeQualification("Valid pattern; entry risk too wide.");events.saveAndFlush(e);
        // A projection is followed with the same rules, so it shows what the signal would have returned.
        var projection=archives.getSignalArchive(u,"trade-return","desc",0).signals().getFirst().outcome();
        assertThat(projection.returnPercent()).isEqualTo(-10);
        assertThat(projection.label()).isEqualTo("Projection · Stop loss reached");
        assertThat(projection.valueLabel()).isEqualTo("90.00 (-10.00%)");
        assertThat(projection.projection()).isTrue();
        // The database ordering uses the same modeled return, so the projection's loss sorts below a gain.
        var won=new AlertEvent();won.setAlertRule(r);won.setPattern(CandlePattern.BULLISH_ENGULFING);won.setTradeSignal(TradeSignal.BUY);won.setClosePrice(100.0);won.setSignalCandleTimestamp(1704153600L);
        won.setTradePlanVersion("CANDLE_RR_V4");won.setTradeEntryPrice(100.0);won.setStructuralStopPrice(95.0);won.setStopLossPrice(94.8);won.setProfitTargetPrice(105.0);won.setRewardRiskRatio(2.0);won.setTradeActionable(true);won.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);events.saveAndFlush(won);
        assertThat(query.page(u.getId(),null,true,0,50).ids()).containsExactly(e.getId(),won.getId());
        assertThat(query.page(u.getId(),null,false,0,50).ids()).containsExactly(won.getId(),e.getId());
        events.delete(won);events.flush();
        // Without a stored stop and target it is measured from the next open to the close 8 candles later.
        e.setStopLossPrice(null);e.setProfitTargetPrice(null);e.setTradePlanVersion(null);events.saveAndFlush(e);
        var waiting=archives.getSignalArchive(u,"trade-return","desc",0).signals().getFirst().outcome();
        assertThat(waiting.pending()).as("no candle after the signal yet").isTrue();
        for(int i=1;i<=10;i++) candles.save(new Candle(a.getTickerSymbol(),"1d",1704067200L+i*86400L,100+i,101.5+i,99+i,100.5+i,100L));
        candles.flush();
        var withoutPlan=archives.getSignalArchive(u,"trade-return","desc",0).signals().getFirst().outcome();
        assertThat(withoutPlan.label()).isEqualTo("Projection · Close after 8 candles");
        assertThat(withoutPlan.returnPercent()).isCloseTo((108.5-101)/101*100, within(1e-9));
    }
}
