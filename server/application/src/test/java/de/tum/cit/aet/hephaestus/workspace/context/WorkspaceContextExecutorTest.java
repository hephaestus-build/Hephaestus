package de.tum.cit.aet.hephaestus.workspace.context;

import static org.junit.jupiter.api.Assertions.*;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@Tag("unit")
class WorkspaceContextExecutorTest {

    @AfterEach
    void cleanup() {
        WorkspaceContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void shouldPropagateContextToRunnable() throws Exception {
        WorkspaceContext context = new WorkspaceContext(
                1L, "test-workspace", "Test", AccountType.ORG, 100L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(context);

        AtomicReference<WorkspaceContext> capturedContext = new AtomicReference<>();

        Runnable wrapped =
                WorkspaceContextExecutor.wrap(() -> capturedContext.set(WorkspaceContextHolder.getContext()));

        // Clear context in main thread to simulate async boundary
        WorkspaceContextHolder.clearContext();

        // Execute in different thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(wrapped);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        WorkspaceContext captured = capturedContext.get();
        assertNotNull(captured);
        assertEquals("test-workspace", captured.slug());
        assertEquals(1L, captured.id());
    }

    @Test
    void shouldPropagateContextToCallable() throws Exception {
        WorkspaceContext workspaceContext =
                new WorkspaceContext(42L, "callable-test", "Test", AccountType.USER, null, false, Set.of());
        WorkspaceContextHolder.setContext(workspaceContext);

        Callable<String> wrapped = WorkspaceContextExecutor.wrap(() -> {
            WorkspaceContext wrappedWorkspaceContext = WorkspaceContextHolder.getContext();
            return wrappedWorkspaceContext != null ? wrappedWorkspaceContext.slug() : "null";
        });

        // Clear context in main thread
        WorkspaceContextHolder.clearContext();

        // Execute in different thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(wrapped);
        String result = future.get();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals("callable-test", result);
    }

    @Test
    void shouldPropagateMDCToRunnable() throws Exception {
        WorkspaceContext context =
                new WorkspaceContext(99L, "mdc-test", "Test", AccountType.ORG, 777L, false, Set.of());
        WorkspaceContextHolder.setContext(context);

        AtomicReference<String> capturedWorkspaceId = new AtomicReference<>();
        AtomicReference<String> capturedSlug = new AtomicReference<>();

        Runnable wrapped = WorkspaceContextExecutor.wrap(() -> {
            capturedWorkspaceId.set(MDC.get("workspace.id"));
            capturedSlug.set(MDC.get("workspace_slug"));
        });

        WorkspaceContextHolder.clearContext();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(wrapped);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals("99", capturedWorkspaceId.get());
        assertEquals("mdc-test", capturedSlug.get());
    }

    @Test
    void shouldCleanupContextAfterRunnableExecution() throws Exception {
        WorkspaceContext context =
                new WorkspaceContext(1L, "cleanup-test", "Test", AccountType.ORG, null, false, Set.of());
        WorkspaceContextHolder.setContext(context);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WorkspaceContext> contextAfterExecution = new AtomicReference<>();

        Runnable wrapped = WorkspaceContextExecutor.wrap(() -> {
            // Do some work
            assertNotNull(WorkspaceContextHolder.getContext());
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            wrapped.run();
            // Check context after wrapped runnable completes
            contextAfterExecution.set(WorkspaceContextHolder.getContext());
            latch.countDown();
        });

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Context should be cleaned up
        assertNull(contextAfterExecution.get());
    }

    @Test
    void shouldHandleNullContextGracefully() throws Exception {
        // Arrange - No context set
        AtomicReference<WorkspaceContext> capturedContext = new AtomicReference<>();

        Runnable wrapped = WorkspaceContextExecutor.wrap(() -> {
            capturedContext.set(WorkspaceContextHolder.getContext());
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(wrapped);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertNull(capturedContext.get());
    }

    @Test
    void shouldThrowExceptionForNullRunnable() {
        assertThrows(IllegalArgumentException.class, () -> {
            WorkspaceContextExecutor.wrap((Runnable) null);
        });
    }

    @Test
    void shouldThrowExceptionForNullCallable() {
        assertThrows(IllegalArgumentException.class, () -> {
            WorkspaceContextExecutor.wrap((Callable<?>) null);
        });
    }

    @Test
    void shouldNotLeakContextBetweenExecutions() throws Exception {
        WorkspaceContext context1 = new WorkspaceContext(1L, "ws1", "WS1", AccountType.ORG, null, false, Set.of());
        WorkspaceContext context2 = new WorkspaceContext(2L, "ws2", "WS2", AccountType.USER, null, false, Set.of());

        AtomicReference<String> capturedSlug1 = new AtomicReference<>();
        AtomicReference<String> capturedSlug2 = new AtomicReference<>();

        // Act - Wrap two different contexts
        WorkspaceContextHolder.setContext(context1);
        Runnable wrapped1 = WorkspaceContextExecutor.wrap(() -> {
            capturedSlug1.set(WorkspaceContextHolder.getContext().slug());
        });

        WorkspaceContextHolder.setContext(context2);
        Runnable wrapped2 = WorkspaceContextExecutor.wrap(() -> {
            capturedSlug2.set(WorkspaceContextHolder.getContext().slug());
        });

        WorkspaceContextHolder.clearContext();

        // Execute both in same thread pool
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(wrapped1);
        executor.submit(wrapped2);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assert - Each should capture its own context
        assertEquals("ws1", capturedSlug1.get());
        assertEquals("ws2", capturedSlug2.get());
    }

    @Test
    void shouldRestorePreviousContextAndMdcAfterExecution() throws Exception {
        WorkspaceContext previousContext =
                new WorkspaceContext(10L, "base", "Base", AccountType.ORG, null, false, Set.of());
        WorkspaceContext wrappedContext =
                new WorkspaceContext(11L, "wrapped", "Wrapped", AccountType.USER, null, false, Set.of());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<WorkspaceContext> contextAfter = new AtomicReference<>();
        AtomicReference<String> preservedValue = new AtomicReference<>();
        AtomicReference<String> wrappedOnlyValue = new AtomicReference<>();

        executor.submit(() -> {
            WorkspaceContextHolder.setContext(previousContext);
            MDC.put("request-id", "abc-123");
        });

        WorkspaceContextHolder.setContext(wrappedContext);
        Runnable wrapped = WorkspaceContextExecutor.wrap(() -> {
            assertEquals("wrapped", WorkspaceContextHolder.getContext().slug());
            MDC.put("wrapped-only", "temp");
        });
        WorkspaceContextHolder.clearContext();

        executor.submit(wrapped);

        executor.submit(() -> {
            contextAfter.set(WorkspaceContextHolder.getContext());
            preservedValue.set(MDC.get("request-id"));
            wrappedOnlyValue.set(MDC.get("wrapped-only"));
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        WorkspaceContext restored = contextAfter.get();
        assertNotNull(restored);
        assertEquals("base", restored.slug());
        assertEquals("abc-123", preservedValue.get());
        assertNull(wrappedOnlyValue.get());
    }
}
