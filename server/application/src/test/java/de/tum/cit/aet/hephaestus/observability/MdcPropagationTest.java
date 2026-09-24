package de.tum.cit.aet.hephaestus.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.core.task.support.TaskExecutorAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;

@Tag("unit")
class MdcPropagationTest {

    @BeforeAll
    static void enableContextPropagation() {
        new MdcContextConfiguration().register();
        Hooks.enableAutomaticContextPropagation();
    }

    private @Nullable Map<String, String> previousMdc;

    @BeforeEach
    void saveMdc() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreMdc() {
        if (previousMdc == null) MDC.clear();
        else MDC.setContextMap(previousMdc);
    }

    @Test
    void shouldPropagateMdcAcrossVirtualThreadTask() throws Exception {
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            TaskExecutorAdapter executor = new TaskExecutorAdapter(executorService);
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            MDC.put("sentinel", "preserved");
            assertEquals("preserved", executor.submit(() -> MDC.get("sentinel")).get());
        }
    }

    @Test
    void shouldPropagateMdcAcrossFluxOperators() {
        MDC.put("sentinel", "preserved");
        AtomicReference<String> observed = new AtomicReference<>();

        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {1, 2, 3});
        Flux.just(buffer)
                .publishOn(Schedulers.boundedElastic())
                .map(DataBuffer::readableByteCount)
                .doOnNext(ignored -> observed.set(MDC.get("sentinel")))
                .blockLast();

        assertEquals("preserved", observed.get());
    }

    @Test
    void shouldRestoreMdcAfterFailureBeforeReusingAWorkerThread() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            TaskExecutorAdapter executor = new TaskExecutorAdapter(worker);
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            assertNull(worker.submit(() -> MDC.get("workspace.id")).get());
            MDC.put("workspace.id", "42");
            var failed = executor.submit(() -> {
                assertEquals("42", MDC.get("workspace.id"));
                MDC.put("workspace.id", "changed-in-task");
                throw new IllegalStateException("expected task failure");
            });
            var failure = assertThrows(ExecutionException.class, failed::get);
            var cause = assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("expected task failure", cause.getMessage());
            assertEquals("42", MDC.get("workspace.id"));
            assertNull(worker.submit(() -> MDC.get("workspace.id")).get());
            MDC.put("workspace.id", "84");
            assertEquals("84", executor.submit(() -> MDC.get("workspace.id")).get());
            assertNull(worker.submit(() -> MDC.get("workspace.id")).get());
        }
    }
}
