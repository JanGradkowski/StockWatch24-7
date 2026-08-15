package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserSignalScoringPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSignalScoringPreferencesRepository
        extends JpaRepository<UserSignalScoringPreferences, Long> {
    Optional<UserSignalScoringPreferences> findByUser(User user);
}
