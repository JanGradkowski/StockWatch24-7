package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {
    List<SecurityEvent> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);
}
