package de.tum.cit.aet.hephaestus.testconfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.graphql.client.ClientGraphQlResponse;
import reactor.core.publisher.Mono;

/** The comment threads of a provider a test controls, including its failures before and after a write. */
public final class ScriptedCommentThreads {

    public record Comment(String id, String body) {}

    private final Map<String, List<Comment>> threads = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicBoolean loseNextWriteResponse = new AtomicBoolean();
    private final AtomicBoolean failNextResolution = new AtomicBoolean();
    private final String idPrefix;
    private volatile boolean lookupsFail;

    public ScriptedCommentThreads(String idPrefix) {
        this.idPrefix = idPrefix;
    }

    public List<Comment> on(String thread) {
        return List.copyOf(threads.getOrDefault(thread, List.of()));
    }

    public void failLookups(boolean fail) {
        lookupsFail = fail;
    }

    public void loseNextWriteResponse() {
        loseNextWriteResponse.set(true);
    }

    public void failNextResolution() {
        failNextResolution.set(true);
    }

    public Mono<ClientGraphQlResponse> resolve(Map<String, ?> fields) {
        return failNextResolution.getAndSet(false)
                ? Mono.error(new IllegalStateException("provider unavailable"))
                : ScriptedGraphQlClient.respond(fields);
    }

    public Mono<ClientGraphQlResponse> write(String thread, String body, Function<String, Map<String, ?>> response) {
        String id = idPrefix + ids.incrementAndGet();
        threads.computeIfAbsent(thread, ignored -> new CopyOnWriteArrayList<>()).add(new Comment(id, body));
        return loseNextWriteResponse.getAndSet(false)
                ? Mono.error(new IllegalStateException("connection reset after the comment landed"))
                : ScriptedGraphQlClient.respond(response.apply(id));
    }

    public Mono<ClientGraphQlResponse> read(String thread, Function<List<Comment>, Map<String, ?>> response) {
        return lookupsFail
                ? Mono.error(new IllegalStateException("provider unavailable"))
                : ScriptedGraphQlClient.respond(response.apply(on(thread)));
    }
}
