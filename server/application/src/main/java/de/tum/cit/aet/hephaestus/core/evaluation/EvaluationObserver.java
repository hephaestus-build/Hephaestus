package de.tum.cit.aet.hephaestus.core.evaluation;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationObservations;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnEvaluation
class EvaluationObserver implements EvaluationObservations, DisposableBean {

    record Observation(long sequence, Instant at, String type, Event event) {}

    record Settled(
            boolean settled, long watermark, List<EvaluationWork.Item> blocking, List<EvaluationWork.Item> future) {}

    private static final long FALLBACK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Comparator<EvaluationWork.Item> WORK_ORDER =
            Comparator.comparing(EvaluationWork.Item::queue).thenComparing(EvaluationWork.Item::id);
    private final ArrayDeque<Observation> pending = new ArrayDeque<>();
    private final RestClient client;
    private final URI collector;
    private final Clock clock;
    private final ScheduledExecutorService sender = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("evaluation-observer").factory());
    private boolean stopped;
    private long revision;
    private long sent;
    private long recorded;
    private final Map<Long, EvaluationWork.Item> callsInFlight = new HashMap<>();
    private long lastCall;
    private long uncommitted;

    EvaluationObserver(RestClient.Builder builder, EvaluationProperties properties, Clock clock) {
        collector = Objects.requireNonNull(properties.collectorUrl(), "Evaluation needs a collector URL");
        if (!("http".equals(collector.getScheme()) || "https".equals(collector.getScheme()))
                || collector.getHost() == null
                || collector.getUserInfo() != null
                || collector.getFragment() != null) {
            throw new IllegalStateException("Evaluation collector URL must be an HTTP(S) endpoint");
        }
        var requestFactory = ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(2))
                        .withRedirects(HttpRedirects.DONT_FOLLOW));
        client = builder.requestFactory(requestFactory).build();
        this.clock = clock;
    }

    @Override
    public synchronized void changed() {
        revision++;
        notifyAll();
    }

    @Override
    public synchronized Call beginCall(CallKind kind, String id) {
        long call = ++lastCall;
        callsInFlight.put(call, new EvaluationWork.Item("fake", id, kind.name(), null));
        changed();
        return () -> endCall(call);
    }

    private synchronized void endCall(long call) {
        callsInFlight.remove(call);
        changed();
    }

    @Override
    public void record(Event event) {
        Instant at = clock.instant();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            append(at, event);
            return;
        }
        synchronized (this) {
            uncommitted++;
            changed();
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                synchronized (EvaluationObserver.this) {
                    if (status == STATUS_COMMITTED) append(at, event);
                    uncommitted--;
                    changed();
                }
            }
        });
    }

    private synchronized void append(Instant at, Event event) {
        if (stopped) return;
        boolean idle = pending.isEmpty();
        pending.addLast(new Observation(++recorded, at, event.getClass().getSimpleName(), event));
        if (idle) scheduleDelivery(0);
        changed();
    }

    @Override
    public synchronized void destroy() {
        stopped = true;
        sender.shutdownNow();
    }

    private synchronized void scheduleDelivery(long delayMillis) {
        if (stopped) return;
        var _ = sender.schedule(this::deliver, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void deliver() {
        while (true) {
            Observation next;
            synchronized (this) {
                if (stopped) return;
                next = pending.peekFirst();
            }
            if (next == null) return;
            try {
                var response = client.post()
                        .uri(collector)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(next)
                        .retrieve()
                        .toBodilessEntity();
                if (!response.getStatusCode().is2xxSuccessful()) {
                    scheduleDelivery(100);
                    return;
                }
            } catch (RestClientException unavailable) {
                // Leave the oldest event pending. A failed handover blocks settle and retries in sequence order.
                scheduleDelivery(100);
                return;
            }
            synchronized (this) {
                pending.removeFirst();
                sent = next.sequence();
                changed();
                if (pending.isEmpty()) return;
            }
        }
    }

    Settled await(EvaluationSnapshot snapshot, Duration timeout) throws InterruptedException {
        long start = System.nanoTime();
        while (true) {
            long before;
            synchronized (this) {
                before = revision;
            }
            List<EvaluationWork.Item> items = snapshot.read();
            synchronized (this) {
                long remaining = timeout.toNanos() - (System.nanoTime() - start);
                if (before == revision || remaining <= 0) {
                    var blocking = blocking(items, before != revision);
                    var future = items.stream()
                            .filter(item -> item.futureDueAt() != null)
                            .sorted(WORK_ORDER)
                            .toList();
                    if (blocking.isEmpty() || remaining <= 0) {
                        return new Settled(blocking.isEmpty(), sent, blocking, future);
                    }
                    TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, FALLBACK_NANOS));
                }
            }
        }
    }

    private List<EvaluationWork.Item> blocking(List<EvaluationWork.Item> items, boolean changedDuringRead) {
        var blocking = new ArrayList<EvaluationWork.Item>();
        items.stream().filter(item -> item.futureDueAt() == null).forEach(blocking::add);
        blocking.addAll(callsInFlight.values());
        if (uncommitted != 0)
            blocking.add(new EvaluationWork.Item("observer", Long.toString(uncommitted), "UNCOMMITTED", null));
        for (Observation event : pending)
            blocking.add(new EvaluationWork.Item("observer", Long.toString(event.sequence()), "UNDELIVERED", null));
        if (changedDuringRead)
            blocking.add(new EvaluationWork.Item("evaluation", "-", "STATE_CHANGED_DURING_SNAPSHOT", null));
        return blocking.stream().sorted(WORK_ORDER).toList();
    }
}
