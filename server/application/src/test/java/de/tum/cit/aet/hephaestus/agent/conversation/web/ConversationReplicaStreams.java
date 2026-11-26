package de.tum.cit.aet.hephaestus.agent.conversation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationReplica;
import de.tum.cit.aet.hephaestus.conversation.ConversationScopeFence;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
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
        var hub = hub(beans, replica, sent::add);
        try {
            hub.subscribe(workspace, owner);
            return Objects.requireNonNull(sent.poll(5, TimeUnit.SECONDS), "The replica did not send its snapshot");
        } finally {
            hub.shutdown();
        }
    }
    /**
     * Pauses the write after its epoch check. A removal on a second connection must wait for the send's transaction.
     */
    public static void removeWhileSending(
            ApplicationContext beans, ConversationReplica replica, long workspace, long owner, Runnable remove)
            throws Exception {
        var entered = new CompletableFuture<Integer>();
        var release = new CompletableFuture<Void>();
        var jdbc = beans.getBean(JdbcTemplate.class);
        var hub = hub(beans, replica, frame -> {
            entered.complete(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            release.orTimeout(15, TimeUnit.SECONDS).join();
        });
        try {
            hub.subscribe(workspace, owner);
            int sender = entered.get(10, TimeUnit.SECONDS);
            var removal = CompletableFuture.runAsync(remove);
            try {
                Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> removal.isDone()
                        || Boolean.TRUE.equals(jdbc.queryForObject("""
                                SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))
                                """, Boolean.class, sender)));
                assertThat(removal).isNotDone();
            } finally {
                release.complete(null);
                removal.get(10, TimeUnit.SECONDS);
            }
        } finally {
            release.complete(null);
            hub.shutdown();
        }
    }

    private static ConversationLiveHub hub(
            ApplicationContext beans, ConversationReplica replica, Consumer<String> outgoing) {
        var emitter = new SseEmitter() {
            @Override
            public void send(Set<DataWithMediaType> data) {
                var wire = new StringBuilder();
                data.forEach(part -> wire.append(part.getData()));
                outgoing.accept(wire.toString());
            }
        };
        return new ConversationLiveHub(
                mock(ConversationLedgerNotifications.class),
                replica.timeline(),
                beans.getBean(ConversationScopeFence.class),
                beans.getBean(AccountMembershipQuery.class),
                beans.getBean(OwnerScope.class),
                beans.getBean(TransactionTemplate.class),
                beans.getBean(ObjectMapper.class),
                new SimpleMeterRegistry(),
                () -> emitter,
                10);
    }
}
