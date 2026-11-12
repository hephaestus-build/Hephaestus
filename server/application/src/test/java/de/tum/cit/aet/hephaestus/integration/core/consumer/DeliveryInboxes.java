package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Consumer lifecycle tests run callbacks without an inbox database. */
final class DeliveryInboxes {
    private DeliveryInboxes() {}

    static DeliveryInbox passthrough() {
        var inbox = mock(DeliveryInbox.class);
        doAnswer(call -> {
                    call.<Runnable>getArgument(3).run();
                    return false;
                })
                .when(inbox)
                .dispatch(anyLong(), anyInt(), any(), any());
        return inbox;
    }
}
