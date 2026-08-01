package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.InsiderActivityRefreshState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InsiderActivityRefreshStateRepository
        extends JpaRepository<InsiderActivityRefreshState, Long> {
}
