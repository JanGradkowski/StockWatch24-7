package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.SecurityEventRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountDeletionServiceTest {

    @Test
    void oneAccountThatCannotBeDeletedDoesNotStopTheOthers() {
        UserRepository users = mock(UserRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        User stuck = expired(1L);
        User next = expired(2L);
        when(users.findExpiredDeletionIds(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(stuck));
        when(users.findByIdForUpdate(2L)).thenReturn(Optional.of(next));
        doThrow(new IllegalStateException("foreign key")).when(users).delete(stuck);

        new AccountDeletionService(users, mock(SecurityEventRepository.class), notifications, "http://localhost")
                .deleteExpiredAccounts();

        verify(users).delete(next);
    }

    @Test
    void anAccountWhoseOwnerCancelledAfterTheIdsWereReadIsLeftAlone() {
        UserRepository users = mock(UserRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        User cancelled = expired(3L);
        cancelled.setDeletionRequestedAt(null);
        when(users.findExpiredDeletionIds(any(), anyInt())).thenReturn(List.of(3L));
        when(users.findByIdForUpdate(3L)).thenReturn(Optional.of(cancelled));

        new AccountDeletionService(users, mock(SecurityEventRepository.class), notifications, "http://localhost")
                .deleteExpiredAccounts();

        verify(users, never()).delete(any(User.class));
        verify(notifications, never()).sendAccountDeletedNotice(any());
    }

    private static User expired(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("deleted-" + id + "@example.com");
        user.setDeletionRequestedAt(LocalDateTime.now().minusDays(8));
        return user;
    }
}
