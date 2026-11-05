package com.processing.cardmanagement.services;

import com.processing.cardmanagement.exceptions.BinAlreadyExistException;
import com.processing.cardmanagement.exceptions.BinNotFoundException;
import com.processing.cardmanagement.models.BinIssuer;
import com.processing.cardmanagement.repositories.BinIssuerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinIssuerServiceImplTest {

    private static final String BIN = "400000";

    @Mock
    private BinIssuerRepository repository;

    private BinIssuerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BinIssuerServiceImpl(repository);
    }

    @Test
    void shouldResolveIssuerIdByBin() {

        when(repository.findByBin(BIN)).thenReturn(Optional.of(new BinIssuer(BIN, "ISS001")));

        assertThat(service.getIssuerId(BIN)).isEqualTo("ISS001");
    }

    @Test
    void shouldFailWithBinNotFoundWhenBinIsNotRegistered() {

        when(repository.findByBin("999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIssuerId("999999"))
                .isInstanceOf(BinNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void shouldReturnAllRegisteredBins() {

        List<BinIssuer> all = List.of(new BinIssuer(BIN, "ISS001"), new BinIssuer("400001", "ISS002"));
        when(repository.findAll()).thenReturn(all);

        assertThat(service.getAll()).isEqualTo(all);
    }

    @Test
    void shouldSaveNewBin() {

        BinIssuer created = new BinIssuer(BIN, "ISS001");
        when(repository.existsByBin(BIN)).thenReturn(false);
        when(repository.save(created)).thenReturn(created);

        assertThat(service.create(BIN, "ISS001")).isEqualTo(created);
    }

    @Test
    void shouldRejectBinThatAlreadyExists() {

        when(repository.existsByBin(BIN)).thenReturn(true);

        assertThatThrownBy(() -> service.create(BIN, "ISS001")).isInstanceOf(BinAlreadyExistException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldDeleteExistingBin() {

        when(repository.existsByBin(BIN)).thenReturn(true);

        service.delete(BIN);

        verify(repository).deleteByBin(BIN);
    }

    @Test
    void shouldRejectDeletingUnknownBin() {

        when(repository.existsByBin("999999")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("999999")).isInstanceOf(BinNotFoundException.class);
        verify(repository, never()).deleteByBin(any());
    }
}
