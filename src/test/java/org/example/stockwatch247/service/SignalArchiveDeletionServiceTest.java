package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SignalArchiveDeletionServiceTest {
    private final AlertEventRepository alertEventRepository = mock(AlertEventRepository.class);
    private final CongressionalTradeDeliveryRepository congressionalRepository =
            mock(CongressionalTradeDeliveryRepository.class);
    private final InsiderTradeDeliveryRepository insiderRepository =
            mock(InsiderTradeDeliveryRepository.class);
    private final SignalArchiveDeletionService service = new SignalArchiveDeletionService(
            alertEventRepository,
            congressionalRepository,
            insiderRepository);

    @Test
    void deletesOwnedTechnicalSignalsAndDeduplicatesTheSelection() {
        User user = new User();
        AlertEvent first = new AlertEvent();
        AlertEvent second = new AlertEvent();
        when(alertEventRepository.findOwnedByIdsAndUser(List.of(17L, 23L), user))
                .thenReturn(List.of(first, second));

        int deleted = service.deleteTechnicalSignals(user, List.of(17L, 23L, 17L));

        assertThat(deleted).isEqualTo(2);
        verify(alertEventRepository).saveAll(List.of(first, second));
        assertThat(first.getDeletedAt()).isNotNull();
        assertThat(second.getDeletedAt()).isEqualTo(first.getDeletedAt());
    }

    @Test
    void technicalDeletionIsAllOrNothingWhenAnySignalIsNotOwned() {
        User user = new User();
        AlertEvent owned = new AlertEvent();
        when(alertEventRepository.findOwnedByIdsAndUser(List.of(17L, 99L), user))
                .thenReturn(List.of(owned));

        assertThatThrownBy(() -> service.deleteTechnicalSignals(user, List.of(17L, 99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not belong to this account");

        verify(alertEventRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void deletesMixedActivitySignalsOnlyAfterBothSourcesPassOwnershipValidation() {
        User user = new User();
        CongressionalTradeDelivery congressional = new CongressionalTradeDelivery();
        InsiderTradeDelivery insider = new InsiderTradeDelivery();
        when(congressionalRepository.findOwnedByIdsAndUser(List.of(31L), user))
                .thenReturn(List.of(congressional));
        when(insiderRepository.findOwnedByIdsAndUser(List.of(42L), user))
                .thenReturn(List.of(insider));

        int deleted = service.deleteActivitySignals(
                user,
                List.of("CONGRESSIONAL:31", "insider:42"));

        assertThat(deleted).isEqualTo(2);
        verify(congressionalRepository).saveAll(List.of(congressional));
        verify(insiderRepository).saveAll(List.of(insider));
        assertThat(congressional.getDeletedAt()).isNotNull();
        assertThat(insider.getDeletedAt()).isEqualTo(congressional.getDeletedAt());
    }

    @Test
    void mixedActivityDeletionDoesNotDeleteEitherSourceWhenOneIdIsNotOwned() {
        User user = new User();
        CongressionalTradeDelivery congressional = new CongressionalTradeDelivery();
        when(congressionalRepository.findOwnedByIdsAndUser(List.of(31L), user))
                .thenReturn(List.of(congressional));
        when(insiderRepository.findOwnedByIdsAndUser(List.of(42L), user))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.deleteActivitySignals(
                user,
                List.of("CONGRESSIONAL:31", "INSIDER:42")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not belong to this account");

        verify(congressionalRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(insiderRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void rejectsMalformedActivityKeysBeforeQueryingEitherArchive() {
        User user = new User();

        assertThatThrownBy(() -> service.deleteActivitySignals(user, List.of("OTHER:12")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The activity signal selection is invalid.");

        verifyNoInteractions(congressionalRepository, insiderRepository);
    }
}
