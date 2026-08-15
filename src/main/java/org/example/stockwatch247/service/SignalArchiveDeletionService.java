package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class SignalArchiveDeletionService {
    private static final int MAX_DELETE_SELECTION = 100;

    private final AlertEventRepository alertEventRepository;
    private final CongressionalTradeDeliveryRepository congressionalDeliveryRepository;
    private final InsiderTradeDeliveryRepository insiderDeliveryRepository;

    public SignalArchiveDeletionService(
            AlertEventRepository alertEventRepository,
            CongressionalTradeDeliveryRepository congressionalDeliveryRepository,
            InsiderTradeDeliveryRepository insiderDeliveryRepository) {
        this.alertEventRepository = alertEventRepository;
        this.congressionalDeliveryRepository = congressionalDeliveryRepository;
        this.insiderDeliveryRepository = insiderDeliveryRepository;
    }

    @Transactional
    public int deleteTechnicalSignals(User user, Collection<Long> requestedIds) {
        requireUser(user);
        List<Long> ids = normalizeIds(requestedIds);
        List<AlertEvent> ownedSignals = alertEventRepository.findOwnedByIdsAndUser(ids, user);
        requireCompleteOwnership(ids.size(), ownedSignals.size());
        Instant deletedAt = Instant.now();
        ownedSignals.forEach(signal -> signal.markDeleted(deletedAt));
        alertEventRepository.saveAll(ownedSignals);
        return ownedSignals.size();
    }

    @Transactional
    public int deleteActivitySignals(User user, Collection<String> requestedKeys) {
        requireUser(user);
        List<ActivitySignalKey> keys = normalizeActivityKeys(requestedKeys);
        List<Long> congressionalIds = keys.stream()
                .filter(key -> key.source() == ActivitySource.CONGRESSIONAL)
                .map(ActivitySignalKey::id)
                .toList();
        List<Long> insiderIds = keys.stream()
                .filter(key -> key.source() == ActivitySource.INSIDER)
                .map(ActivitySignalKey::id)
                .toList();

        List<CongressionalTradeDelivery> congressionalDeliveries = congressionalIds.isEmpty()
                ? List.of()
                : congressionalDeliveryRepository.findOwnedByIdsAndUser(congressionalIds, user);
        List<InsiderTradeDelivery> insiderDeliveries = insiderIds.isEmpty()
                ? List.of()
                : insiderDeliveryRepository.findOwnedByIdsAndUser(insiderIds, user);
        requireCompleteOwnership(
                keys.size(), congressionalDeliveries.size() + insiderDeliveries.size());

        Instant deletedAt = Instant.now();
        congressionalDeliveries.forEach(delivery -> delivery.markDeleted(deletedAt));
        insiderDeliveries.forEach(delivery -> delivery.markDeleted(deletedAt));
        congressionalDeliveryRepository.saveAll(congressionalDeliveries);
        insiderDeliveryRepository.saveAll(insiderDeliveries);
        return congressionalDeliveries.size() + insiderDeliveries.size();
    }

    private List<Long> normalizeIds(Collection<Long> requestedIds) {
        if (requestedIds == null) {
            throw new IllegalArgumentException("Select at least one signal to delete.");
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>(requestedIds);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Select at least one signal to delete.");
        }
        if (ids.size() > MAX_DELETE_SELECTION) {
            throw new IllegalArgumentException("Delete at most 100 signals at once.");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0L)) {
            throw new IllegalArgumentException("The signal selection is invalid.");
        }
        return List.copyOf(ids);
    }

    private List<ActivitySignalKey> normalizeActivityKeys(Collection<String> requestedKeys) {
        if (requestedKeys == null) {
            throw new IllegalArgumentException("Select at least one activity signal to delete.");
        }
        LinkedHashSet<ActivitySignalKey> keys = new LinkedHashSet<>();
        for (String rawKey : requestedKeys) {
            keys.add(parseActivityKey(rawKey));
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("Select at least one activity signal to delete.");
        }
        if (keys.size() > MAX_DELETE_SELECTION) {
            throw new IllegalArgumentException("Delete at most 100 activity signals at once.");
        }
        return List.copyOf(keys);
    }

    private ActivitySignalKey parseActivityKey(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        int separator = key.indexOf(':');
        if (separator <= 0 || separator == key.length() - 1) {
            throw new IllegalArgumentException("The activity signal selection is invalid.");
        }
        try {
            ActivitySource source = ActivitySource.valueOf(
                    key.substring(0, separator).toUpperCase(Locale.ROOT));
            long id = Long.parseLong(key.substring(separator + 1));
            if (id <= 0L) {
                throw new NumberFormatException("Non-positive id");
            }
            return new ActivitySignalKey(source, id);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The activity signal selection is invalid.");
        }
    }

    private void requireUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User is required.");
        }
    }

    private void requireCompleteOwnership(int requestedCount, int ownedCount) {
        if (requestedCount != ownedCount) {
            throw new IllegalArgumentException(
                    "One or more selected signals no longer exist or do not belong to this account.");
        }
    }

    private enum ActivitySource {
        CONGRESSIONAL,
        INSIDER
    }

    private record ActivitySignalKey(ActivitySource source, long id) {
    }
}
