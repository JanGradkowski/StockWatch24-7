package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserElliottWavePreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserElliottWavePreferencesRepository
        extends JpaRepository<UserElliottWavePreferences, Long> {
    Optional<UserElliottWavePreferences> findByUser(User user);
}
