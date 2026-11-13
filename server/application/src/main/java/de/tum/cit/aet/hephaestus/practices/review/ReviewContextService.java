package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewContextDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewContextService {
    static final String MALFORMED_ADDRESS = "Enter an absolute HTTPS address of an item.";
    static final String NO_WORK_AT_ADDRESS = "This workspace has no reviewed work at this address.";
    private final ProductItemQuery items;
    private final ProductReferenceQuery references;
    private final ReviewRequestService requests;
    private final WorkspaceQueries workspaces;
    private final WorkspaceAccessService access;

    public ReviewContextDTO resolve(long workspaceId, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(MALFORMED_ADDRESS, error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException(MALFORMED_ADDRESS);
        var item = items.atUrl(workspaceId, url).orElseThrow(() -> new EntityNotFoundException(NO_WORK_AT_ADDRESS));
        var account = SecurityUtils.getCurrentAccountId().orElseThrow();
        boolean activeUserView = UserViewContextHolder.get() != null;
        boolean allowed = !activeUserView && requests.mayRequest(workspaceId, items.pin(item, null), account);
        return new ReviewContextDTO(
                Long.toString(workspaceId),
                workspaces.findById(workspaceId).orElseThrow().slug(),
                references.items(workspaceId, List.of(item.itemId())).get(item.itemId()),
                allowed,
                !activeUserView && access.isAdmin(),
                allowed ? null : "Only the author or an assignee, or a workspace admin, can request this review.");
    }
}
