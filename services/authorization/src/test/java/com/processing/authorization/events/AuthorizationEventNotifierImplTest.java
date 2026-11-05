package com.processing.authorization.events;

import com.processing.authorization.listeners.AuthorizationEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthorizationEventNotifierImplTest {

    @Mock
    private AuthorizationEventListener firstListener;

    @Mock
    private AuthorizationEventListener secondListener;

    @Test
    void shouldDeliverEventToEveryListener() {

        AuthorizationEventNotifierImpl notifier =
                new AuthorizationEventNotifierImpl(List.of(firstListener, secondListener));
        AuthorizationEvent event = new AuthServiceAuthorizeEvent("4000001234567890");

        notifier.notify(event);

        verify(firstListener).onEvent(event);
        verify(secondListener).onEvent(event);
    }

    @Test
    void shouldDoNothingWhenThereAreNoListeners() {

        AuthorizationEventNotifierImpl notifier = new AuthorizationEventNotifierImpl(List.of());

        notifier.notify(new AuthServiceAuthorizeEvent("4000001234567890"));

        verifyNoInteractions(firstListener, secondListener);
    }
}
