package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CongressionalSubscriptionManager {
    private final UserRepository userRepository;
    private final CongressionalTradeSubscriptionRepository subscriptionRepository;
    private final CongressionalTradeStore tradeStore;

    public CongressionalSubscriptionManager(
            UserRepository userRepository,
            CongressionalTradeSubscriptionRepository subscriptionRepository,
            CongressionalTradeStore tradeStore) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tradeStore = tradeStore;
    }

    @Transactional
    public SubscriptionChange setFollowing(
            User requestingUser,
            StockAsset stockAsset,
            boolean active,
            int maximumFollows) {
        User user = userRepository.findByIdForUpdate(requestingUser.getId())
                .orElseThrow(() -> new IllegalStateException("The signed-in account no longer exists."));
        CongressionalTradeSubscription subscription = subscriptionRepository
                .findByUserAndStockAsset(user, stockAsset)
                .orElse(null);
        Instant now = Instant.now();
        boolean needsBaseline = false;

        if (active) {
            if (subscription == null || !subscription.isActive()) {
                if (subscriptionRepository.countByUserAndActiveTrue(user) >= maximumFollows) {
                    throw new IllegalStateException(
                            "The maximum number of congressional activity follows has been reached.");
                }
                if (subscription == null) {
                    subscription = new CongressionalTradeSubscription();
                    subscription.setUser(user);
                    subscription.setStockAsset(stockAsset);
                    subscription.setCreatedAt(now);
                }
                subscription.setActive(true);
                subscription.setActivatedAt(now);
                subscription.setBaselineCompletedAt(null);
                needsBaseline = true;
            }
        } else if (subscription != null && subscription.isActive()) {
            subscription.setActive(false);
        }

        if (subscription == null) {
            return new SubscriptionChange(null, false, false);
        }
        subscription.setUpdatedAt(now);
        CongressionalTradeSubscription saved = subscriptionRepository.save(subscription);
        if (!active) {
            tradeStore.cancelUnsentDeliveries(saved.getId());
        }
        return new SubscriptionChange(saved.getId(), saved.isActive(), needsBaseline);
    }

    @Transactional
    public void markBaselineComplete(long subscriptionId) {
        subscriptionRepository.findById(subscriptionId).ifPresent(subscription -> {
            if (subscription.isActive() && subscription.getBaselineCompletedAt() == null) {
                Instant now = Instant.now();
                subscription.setBaselineCompletedAt(now);
                subscription.setUpdatedAt(now);
                subscriptionRepository.save(subscription);
            }
        });
    }

    public record SubscriptionChange(Long subscriptionId, boolean active, boolean needsBaseline) {
    }
}
