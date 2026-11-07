package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Consumer lifecycle tests run callbacks without an inbox database. */
final class DeliveryInboxes {
    private DeliveryInboxes() {}

    static DeliveryInbox passthrough() {
        var inbox = mock(DeliveryInbox.class);
        doAnswer(call -> {
                    call.<Runnable>getArgument(2).run();
                    return null;
                })
                .when(inbox)
                .dispatch(any(), any(), any());
        return inbox;
    }
}
