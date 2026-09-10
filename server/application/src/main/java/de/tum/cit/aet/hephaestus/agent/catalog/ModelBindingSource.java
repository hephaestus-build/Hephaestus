package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import org.jspecify.annotations.Nullable;

/**
 * Binds a workspace to exactly one catalog model — the shape {@link LlmModelResolver} needs without
 * depending on {@code agent.config}. Exactly one of the two model getters is non-null for a usable
 * binding.
 */
public interface ModelBindingSource {
    LlmProcessingLocation getProcessingLocation();

    @Nullable
    LlmModel getInstanceModel();

    @Nullable
    WorkspaceLlmModel getWorkspaceModel();

    Workspace getWorkspace();

    boolean isEnabled();

    boolean isAllowInternet();

    int getTimeoutSeconds();
}
