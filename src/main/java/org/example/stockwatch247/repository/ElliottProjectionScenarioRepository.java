package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.ElliottProjectionScenario;
import org.example.stockwatch247.model.ElliottProjectionSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ElliottProjectionScenarioRepository extends JpaRepository<ElliottProjectionScenario, Long> {
    List<ElliottProjectionScenario> findByProjectionSetOrderByDisplayRankAscIdAsc(
            ElliottProjectionSet projectionSet);
}
