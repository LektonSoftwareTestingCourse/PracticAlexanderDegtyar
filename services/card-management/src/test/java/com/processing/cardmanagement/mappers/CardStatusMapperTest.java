package com.processing.cardmanagement.mappers;

import com.processing.cardmanagement.models.CardStatus;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardStatusMapperTest {

    private final CardStatusMapper mapper = new CardStatusMapperImpl();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"DELETED"})
    void shouldMapEveryVisibleDomainStatusToTheApiStatusWithTheSameName(CardStatus status) {

        CardModelStatus apiStatus = mapper.toCardModelStatus(status);

        assertThat(apiStatus.name()).isEqualTo(status.name());
    }

    @Test
    void shouldRefuseToExposeDeletedStatusThroughTheApi() {

        assertThatThrownBy(() -> mapper.toCardModelStatus(CardStatus.DELETED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELETED");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CardModelStatus.class)
    void shouldMapEveryApiStatusToTheDomainStatusWithTheSameName(CardModelStatus apiStatus) {

        CardStatus status = mapper.toCardStatus(apiStatus);

        assertThat(status.name()).isEqualTo(apiStatus.name());
    }
}
