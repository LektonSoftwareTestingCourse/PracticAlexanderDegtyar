package com.processing.authorization.services;

import com.processing.authorization.repositories.LimitUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CleanupServiceImplTest {

    @Mock
    private LimitUsageRepository limitUsageRepository;

    private CleanupServiceImpl cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new CleanupServiceImpl(limitUsageRepository);
    }

    @Test
    void shouldDeleteExactlyThePreviousCalendarMonth() {

        when(limitUsageRepository.deleteByUsageDateBetween(any(), any())).thenReturn(5);
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        LocalDate previousMonth = LocalDate.now(ZoneOffset.UTC).minusMonths(1);

        cleanupService.cleanPreviousMonthRecords();

        verify(limitUsageRepository).deleteByUsageDateBetween(from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(previousMonth.withDayOfMonth(1));
        assertThat(to.getValue()).isEqualTo(previousMonth.withDayOfMonth(previousMonth.lengthOfMonth()));
    }

    @Test
    void shouldNotPropagateRepositoryFailure() {

        when(limitUsageRepository.deleteByUsageDateBetween(any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> cleanupService.cleanPreviousMonthRecords()).doesNotThrowAnyException();
    }
}
