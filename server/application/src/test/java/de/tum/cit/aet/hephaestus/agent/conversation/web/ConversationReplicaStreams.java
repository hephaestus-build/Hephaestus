package de.tum.cit.aet.hephaestus.agent.conversation.web;

import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationReplica;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

public final class ConversationReplicaStreams {
    private ConversationReplicaStreams() {}

    /** Opens a real hub stream with its own fresh snapshot while removal notifications are deliberately withheld. */
    public static String reconnect(ApplicationContext beans, ConversationReplica replica, long workspace, long owner)
            throws InterruptedException {
        var sent = new ArrayBlockingQueue<String>(1);
        var emitter = new SseEmitter() {
            @Override
            public void send(Set<DataWithMediaType> data) {
                var wire = new StringBuilder();
                data.forEach(part -> wire.append(part.getData()));
                sent.add(wire.toString());
            }
        };
        var hub = new ConversationLiveHub(
                mock(ConversationLedgerNotifications.class),
                replica.timeline(),
                beans.getBean(AccountMembershipQuery.class),
                beans.getBean(OwnerScope.class),
                beans.getBean(TransactionTemplate.class),
                beans.getBean(ObjectMapper.class),
                new SimpleMeterRegistry(),
                () -> emitter,
                10);
        try {
            hub.subscribe(workspace, owner);
            return Objects.requireNonNull(sent.poll(5, TimeUnit.SECONDS), "The replica did not send its snapshot");
        } finally {
            hub.shutdown();
        }
    }
}
