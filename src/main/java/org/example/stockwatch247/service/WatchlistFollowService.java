package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.congress.CongressionalActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** Transaction boundary for membership and monitoring changes from stock pages. */
@Service
public class WatchlistFollowService {
    @org.springframework.beans.factory.annotation.Autowired
    private WatchlistSignalSettingsService settings;
    private final WatchlistService lists;
    private final AlertRuleService alerts;
    private final TechnicalOutlookTrackingService outlooks;
    private final CongressionalActivityService congress;
    private final InsiderActivityService insiders;
    public WatchlistFollowService(WatchlistService lists,AlertRuleService alerts,TechnicalOutlookTrackingService outlooks,
                                 CongressionalActivityService congress,InsiderActivityService insiders) {
        this.lists=lists;this.alerts=alerts;this.outlooks=outlooks;this.congress=congress;this.insiders=insiders;
    }
    private List<Long> prepare(User user,String symbol,boolean active,List<Long> ids,String name) {
        lists.lock(user);
        if(!active) return List.of();
        var before=lists.memberships(user,symbol);
        if((ids!=null && !ids.isEmpty()) || (name!=null && !name.isBlank())) lists.attach(user,symbol,ids,name);
        else lists.requireMembership(user,symbol);
        var after=lists.memberships(user,symbol);
        if((ids==null || ids.isEmpty()) && (name==null || name.isBlank())) return after;
        return after.stream().filter(id -> !before.contains(id) || ids!=null && ids.contains(id)).toList();
    }
    @Transactional
    public Map<String,Object> apply(User user,String symbol,List<AlertRuleService.AlertRuleChange> changes,List<Long> ids,String name) {
        var selected=prepare(user,symbol,changes.stream().anyMatch(AlertRuleService.AlertRuleChange::active),ids,name);
        alerts.applyAlertChanges(user,symbol,changes);
        changes.forEach(change -> settings.record(user,symbol,selected,change.patternFamily()==null?"CANDLESTICK":change.patternFamily().name(),change.interval().name(),change.signal().name(),change.active()));
        return alerts.getAlertState(user,symbol);
    }

    @Transactional
    public Map<String, Object> applyDraft(User user, String symbol,
            List<AlertRuleService.AlertRuleChange> changes, List<OutlookChange> outlookChanges,
            List<Long> ids, String name) {
        var outlookDraft = outlookChanges == null ? List.<OutlookChange>of() : outlookChanges;
        if (changes == null || (changes.isEmpty() && outlookDraft.isEmpty()) || outlookDraft.size() > 3) {
            throw new IllegalArgumentException("Choose alert or outlook changes to apply.");
        }
        var intervals = new java.util.HashSet<TimeInterval>();
        for (var change : outlookDraft) {
            if (change == null || change.interval() == null || !intervals.add(change.interval())) {
                throw new IllegalArgumentException("Choose each outlook interval once.");
            }
        }
        var selected=prepare(user, symbol, changes.stream().anyMatch(AlertRuleService.AlertRuleChange::active)
                || outlookDraft.stream().anyMatch(OutlookChange::active), ids, name);
        if (!changes.isEmpty()) alerts.applyAlertChanges(user, symbol, changes);
        for (var change : outlookDraft) outlooks.setSubscription(user, symbol, change.interval(), change.active());
        changes.forEach(change -> settings.record(user,symbol,selected,change.patternFamily()==null?"CANDLESTICK":change.patternFamily().name(),change.interval().name(),change.signal().name(),change.active()));
        outlookDraft.forEach(change -> settings.record(user,symbol,selected,"OUTLOOK",change.interval().name(),"ANY",change.active()));
        var state = new java.util.HashMap<>(alerts.getAlertState(user, symbol));
        state.put("outlookState", outlooks.getState(user, symbol));
        return state;
    }

    public record OutlookChange(TimeInterval interval, boolean active) {}
    @Transactional
    public TechnicalOutlookTrackingService.SubscriptionStateView outlook(User user,String symbol,TimeInterval interval,boolean active,List<Long> ids,String name) {
        var selected=prepare(user,symbol,active,ids,name);
        var state=outlooks.setSubscription(user,symbol,interval,active);
        settings.record(user,symbol,selected,"OUTLOOK",interval.name(),"ANY",active);
        return state;
    }
    @Transactional
    public CongressionalActivityService.ActivityState congress(User user,String symbol,boolean active,List<Long> ids,String name) {
        var selected=prepare(user,symbol,active,ids,name);
        var state=congress.setFollowing(user,symbol,active);
        settings.record(user,symbol,selected,"CONGRESS","DAILY","ANY",active);
        return state;
    }
    @Transactional
    public InsiderActivityService.ActivityState insider(User user,String symbol,boolean active,List<Long> ids,String name) {
        var selected=prepare(user,symbol,active,ids,name);
        var state=insiders.setFollowing(user,symbol,active);
        settings.record(user,symbol,selected,"INSIDER","DAILY","ANY",active);
        return state;
    }
}
