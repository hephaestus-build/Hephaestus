package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/**
 * A second filter at the workspace boundary that looks up a workspace of any kind without the owner check, for
 * {@code WorkspaceKindArchitectureTest}. Not a bean, and never registered.
 */
public final class AnotherWorkspaceFilterFixture implements Filter {

    private final WorkspaceRepository workspaces;

    public AnotherWorkspaceFilterFixture(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (workspaces.findAnyKindByWorkspaceSlug(request.getServerName()).isPresent()) {
            chain.doFilter(request, response);
        }
    }
}
