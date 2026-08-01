package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {
    List<MfaRecoveryCode> findByUserIdAndUsedAtIsNull(Long userId);
    void deleteByUserId(Long userId);
}
