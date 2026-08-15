package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.UserAnalysisPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAnalysisPreferencesRepository extends JpaRepository<UserAnalysisPreferences, Long> {
    Optional<UserAnalysisPreferences> findByUser(User user);
    Optional<UserAnalysisPreferences> findByUser_Id(Long userId);
}
