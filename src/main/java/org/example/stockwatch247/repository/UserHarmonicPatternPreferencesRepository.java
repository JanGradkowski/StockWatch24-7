package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserHarmonicPatternPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserHarmonicPatternPreferencesRepository
        extends JpaRepository<UserHarmonicPatternPreferences, Long> {
    Optional<UserHarmonicPatternPreferences> findByUser(User user);
}
