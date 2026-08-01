package org.example.stockwatch247.repository;

import jakarta.persistence.LockModeType;
import org.example.stockwatch247.model.PasswordSecurityCode;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PasswordSecurityCodeRepository extends JpaRepository<PasswordSecurityCode, Long> {
    Optional<PasswordSecurityCode> findByUserIdAndPurpose(Long userId, String purpose);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PasswordSecurityCode c where c.user.id = :userId and c.purpose = :purpose")
    Optional<PasswordSecurityCode> findForUpdate(@Param("userId") Long userId, @Param("purpose") String purpose);
    void deleteByUserIdAndPurpose(Long userId, String purpose);
}
