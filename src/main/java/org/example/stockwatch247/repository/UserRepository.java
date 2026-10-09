package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByVerificationTokenHash(String verificationTokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByDeletionCancelTokenHash(String deletionCancelTokenHash);
    List<User> findByDeletionRequestedAtLessThanEqual(LocalDateTime cutoff);
    @Query(value = "select id from users where deletion_requested_at <= :cutoff order by id limit :limit", nativeQuery = true)
    List<Long> findExpiredDeletionIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
