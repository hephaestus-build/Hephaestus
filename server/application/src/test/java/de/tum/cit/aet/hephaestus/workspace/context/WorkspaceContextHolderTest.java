package de.tum.cit.aet.hephaestus.workspace.context;

import static org.junit.jupiter.api.Assertions.*;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@Tag("unit")
class WorkspaceContextHolderTest {

    @AfterEach
    void cleanup() {
        WorkspaceContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void shouldStoreAndRetrieveContext() {
        WorkspaceContext context = new WorkspaceContext(
                1L, "test-workspace", "Test Workspace", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));

        WorkspaceContextHolder.setContext(context);
        WorkspaceContext retrieved = WorkspaceContextHolder.getContext();

        assertNotNull(retrieved);
        assertEquals(1L, retrieved.id());
        assertEquals("test-workspace", retrieved.slug());
        assertEquals("Test Workspace", retrieved.displayName());
        assertEquals(AccountType.ORG, retrieved.accountType());
        assertEquals(123L, retrieved.installationId());
        assertTrue(retrieved.hasRole(WorkspaceRole.OWNER));
    }

    @Test
    void shouldEnrichMDC() {
        WorkspaceContext context =
                new WorkspaceContext(42L, "test-slug", "Test", AccountType.USER, 999L, false, Set.of());

        WorkspaceContextHolder.setContext(context);

        assertEquals("42", MDC.get("workspace.id"));
        assertEquals("test-slug", MDC.get("workspace_slug"));
        assertEquals("999", MDC.get("installation_id"));
    }

    @Test
    void shouldClearContextAndMDC() {
        WorkspaceContext context = new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 100L, false, Set.of());
        WorkspaceContextHolder.setContext(context);

        WorkspaceContextHolder.clearContext();

        assertNull(WorkspaceContextHolder.getContext());
        assertNull(MDC.get("workspace.id"));
        assertNull(MDC.get("workspace_slug"));
        assertNull(MDC.get("installation_id"));
    }

    @Test
    void shouldHandleNullInstallationId() {
        WorkspaceContext context = new WorkspaceContext(
                1L,
                "test",
                "Test",
                AccountType.ORG,
                null, // No installation ID
                false,
                Set.of());

        WorkspaceContextHolder.setContext(context);

        assertEquals("1", MDC.get("workspace.id"));
        assertEquals("test", MDC.get("workspace_slug"));
        assertNull(MDC.get("installation_id"));
    }

    @Test
    void shouldIsolateContextBetweenThreads() throws InterruptedException {
        WorkspaceContext mainContext = new WorkspaceContext(
                1L, "main-workspace", "Main", AccountType.ORG, 100L, false, Set.of(WorkspaceRole.OWNER));

        WorkspaceContext otherContext = new WorkspaceContext(
                2L, "other-workspace", "Other", AccountType.USER, 200L, false, Set.of(WorkspaceRole.MEMBER));

        WorkspaceContextHolder.setContext(mainContext);

        // Act - Create another thread and set different context
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            WorkspaceContextHolder.setContext(otherContext);
            WorkspaceContext retrieved = WorkspaceContextHolder.getContext();

            // Assert in other thread
            assertEquals("other-workspace", retrieved.slug());
            assertEquals(2L, retrieved.id());

            WorkspaceContextHolder.clearContext();
        });

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // Assert - Main thread context should be unchanged
        WorkspaceContext mainRetrieved = WorkspaceContextHolder.getContext();
        assertNotNull(mainRetrieved);
        assertEquals("main-workspace", mainRetrieved.slug());
        assertEquals(1L, mainRetrieved.id());
    }

    @Test
    void shouldReturnNullWhenNoContextSet() {
        WorkspaceContext context = WorkspaceContextHolder.getContext();

        assertNull(context);
    }

    @Test
    void shouldHandleSettingNullContext() {
        WorkspaceContext context = new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 100L, false, Set.of());
        WorkspaceContextHolder.setContext(context);

        WorkspaceContextHolder.setContext(null);

        assertNull(WorkspaceContextHolder.getContext());
        assertNull(MDC.get("workspace.id"));
        assertNull(MDC.get("workspace_slug"));
    }
}
