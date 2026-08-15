package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserCandlestickPatternPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserCandlestickPatternPreferencesRepository
        extends JpaRepository<UserCandlestickPatternPreferences, Long> {
    Optional<UserCandlestickPatternPreferences> findByUser(User user);
}
