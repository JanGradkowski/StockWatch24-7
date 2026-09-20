package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class WatchlistEditorService {
    @org.springframework.beans.factory.annotation.Autowired private WatchlistAllowedSignals permissions;
    private final WatchlistService lists;
    private final WatchlistSignalSettingsService settings;
    private final WatchlistImportService imports;
    public WatchlistEditorService(WatchlistService lists,WatchlistSignalSettingsService settings,WatchlistImportService imports) {
        this.lists=lists;this.settings=settings;this.imports=imports;
    }
    public record SaveRequest(Long id,String name,String description,boolean pinned,List<String> symbols,List<String> indexIds,
            List<WatchlistSignalSettingsService.Selection> selections,boolean applyMonitoring,boolean settingsChanged,List<String> allowedTypes) {
        public SaveRequest(Long id,String name,String description,boolean pinned,List<String> symbols,List<String> indexIds,
                List<WatchlistSignalSettingsService.Selection> selections,boolean applyMonitoring,boolean settingsChanged) {
            this(id,name,description,pinned,symbols,indexIds,selections,applyMonitoring,settingsChanged,null);
        }
    }
    public record Saved(long id,Long importId) {}
    @Transactional
    public Saved save(User user,SaveRequest request) {
        if(request==null || request.symbols()==null || request.indexIds()==null) throw new IllegalArgumentException("Choose valid watchlist changes.");
        lists.lock(user);
        boolean created=request.id()==null;
        if(!created) lists.requireOwned(user,request.id());
        long id=created?lists.create(user,request.name(),request.description()):request.id();
        lists.update(user,id,request.name(),request.description(),request.pinned());
        permissions.save(user,id,request.allowedTypes());
        if(created || request.settingsChanged() || request.applyMonitoring()
                || !settings.configured(id) && (!request.symbols().isEmpty() || !request.indexIds().isEmpty())) settings.save(user,id,request.selections());
        boolean existing=request.applyMonitoring() && !lists.symbols(user,id).isEmpty();
        Long job=null;
        if(existing || !request.symbols().isEmpty() || !request.indexIds().isEmpty()) {
            var effective=settings.get(user,id).selections();
            job=imports.enqueue(user,id,new WatchlistImportService.ImportRequest(request.symbols(),request.indexIds(),false,List.of(),List.of(),effective,existing,created || request.applyMonitoring()));
        }
        return new Saved(id,job);
    }
}
