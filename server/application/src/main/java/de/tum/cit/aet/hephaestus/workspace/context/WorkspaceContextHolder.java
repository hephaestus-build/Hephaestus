package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * ThreadLocal holder for workspace context with MDC enrichment.
 * Ensures workspace isolation and provides observability through structured logging.
 */
public final class WorkspaceContextHolder {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextHolder.class);

    private static final String MDC_WORKSPACE_ID = StructuredLogKeys.WORKSPACE_ID;
    // The id, not the slug: a slug is chosen text and can be a person's login, and it would ride on every line.
    private static final String MDC_INSTALLATION_ID = StructuredLogKeys.INSTALLATION_ID;

    private static final ThreadLocal<WorkspaceContext> contextHolder = new ThreadLocal<>();

    private WorkspaceContextHolder() {
        // Utility class
    }

    /**
     * Set the workspace context for the current thread.
     * Enriches MDC with workspace metadata for logging.
     * Warns if a context is already set (potential context leak).
     *
     * @param context WorkspaceContext to set
     */
    public static void setContext(@Nullable WorkspaceContext context) {
        WorkspaceContext existing = contextHolder.get();
        if (existing != null && !existing.equals(context)) {
            log.warn(
                    "Detected context overwrite: existingWorkspaceId={}, newWorkspaceId={}",
                    existing.id(),
                    context != null ? context.id() : "null");
        }

        contextHolder.set(context);

        if (context != null) {
            enrichMDC(context);
        } else {
            clearMDC();
        }
    }

    /**
     * Get the workspace context for the current thread.
     *
     * @return WorkspaceContext or null if not set
     */
    public static WorkspaceContext getContext() {
        return contextHolder.get();
    }

    /**
     * Clear the workspace context and MDC for the current thread.
     * Must be called in finally blocks to prevent context leaks.
     */
    public static void clearContext() {
        contextHolder.remove();
        clearMDC();
    }

    /**
     * Enrich MDC with workspace metadata for structured logging.
     *
     * @param context WorkspaceContext to extract metadata from
     */
    private static void enrichMDC(WorkspaceContext context) {
        MDC.put(MDC_WORKSPACE_ID, String.valueOf(context.id()));

        if (context.installationId() != null) {
            MDC.put(MDC_INSTALLATION_ID, String.valueOf(context.installationId()));
        } else {
            MDC.remove(MDC_INSTALLATION_ID);
        }
    }

    /**
     * Clear workspace-related MDC keys.
     */
    private static void clearMDC() {
        MDC.remove(MDC_WORKSPACE_ID);
        MDC.remove(MDC_INSTALLATION_ID);
    }
}
