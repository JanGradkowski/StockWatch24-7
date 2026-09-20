package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.congress.CongressionalActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.example.stockwatch247.service.WatchlistSignalSettingsService.*;

@Service
public class WatchlistMonitoringService {
    @org.springframework.beans.factory.annotation.Autowired private WatchlistAllowedSignals permissions;
    private final WatchlistSignalSettingsService settings;
    private final StockAssetRepository assets;
    private final AlertRuleService alerts;
    private final TechnicalOutlookTrackingService outlooks;
    private final CongressionalActivityService congress;
    private final InsiderActivityService insiders;

    public WatchlistMonitoringService(WatchlistSignalSettingsService settings, StockAssetRepository assets,
            AlertRuleService alerts, TechnicalOutlookTrackingService outlooks,
            CongressionalActivityService congress, InsiderActivityService insiders) {
        this.settings=settings; this.assets=assets; this.alerts=alerts;
        this.outlooks=outlooks; this.congress=congress; this.insiders=insiders;
    }

    @EventListener
    @Transactional
    public void membershipChanged(MembershipChanged event) {
        var asset = assets.findById(event.assetId()).orElseThrow();
        for (long list : event.addedLists()) {
            if (settings.configured(list)) settings.applyDefaults(list,event.assetId(),asset.getInstrumentType()==InstrumentType.EQUITY);
            else settings.active(event.user(),event.assetId()).stream().filter(value -> permissions.allows(list, value.type())).forEach(value -> settings.add(list,event.assetId(),value));
        }
        synchronize(event.user(),event.assetId());
    }

    @Transactional
    public void applyDefaults(User user, long listId, long assetId) {
        var asset=assets.findById(assetId).orElseThrow();
        settings.applyDefaults(listId,assetId,asset.getInstrumentType()==InstrumentType.EQUITY);
        synchronize(user,assetId);
    }

    public void synchronize(User user, long assetId) {
        var asset=assets.findById(assetId).orElseThrow();
        Map<String,Selection> active=new HashMap<>(), requested=new HashMap<>();
        settings.active(user,assetId).forEach(value -> active.put(value.key(),value));
        settings.requested(user,assetId).forEach(value -> requested.put(value.key(),value));
        Set<String> keys=new HashSet<>(active.keySet()); keys.addAll(requested.keySet());
        List<AlertRuleService.AlertRuleChange> patterns=new ArrayList<>();
        for(String key:keys) {
            boolean enabled=requested.containsKey(key);
            if(active.containsKey(key)==enabled) continue;
            Selection value=enabled?requested.get(key):active.get(key);
            switch(value.type()) {
                case "OUTLOOK" -> outlooks.setSubscription(user,asset.getTickerSymbol(),TimeInterval.valueOf(value.interval()),enabled);
                case "CONGRESS" -> congress.setFollowing(user,asset.getTickerSymbol(),enabled);
                case "INSIDER" -> insiders.setFollowing(user,asset.getTickerSymbol(),enabled);
                default -> patterns.add(new AlertRuleService.AlertRuleChange(TimeInterval.valueOf(value.interval()),
                        TradeSignal.valueOf(value.direction()),AlertPatternFamily.valueOf(value.type()),enabled));
            }
        }
        if(!patterns.isEmpty()) alerts.applyAlertChanges(user,asset.getTickerSymbol(),patterns);
    }
}
