package de.tum.cit.aet.hephaestus.integration.outline.identity;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery.WorkspaceMembershipView;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves authors at projection time so later identity linking is reflected without rewriting documents.
 * Server, team and subject identify the provider account; workspace membership bounds attribution.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineIdentityResolver {

    private final GitProviderRegistry gitProviderRegistry;
    private final AccountIdentityQuery accountIdentityQuery;
    private final AccountWorkspaceMembershipQuery workspaceMembershipQuery;

    public OutlineIdentityResolver(
            GitProviderRegistry gitProviderRegistry,
            AccountIdentityQuery accountIdentityQuery,
            AccountWorkspaceMembershipQuery workspaceMembershipQuery) {
        this.gitProviderRegistry = gitProviderRegistry;
        this.accountIdentityQuery = accountIdentityQuery;
        this.workspaceMembershipQuery = workspaceMembershipQuery;
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolveMemberId(
            long workspaceId, String serverUrl, @Nullable String teamId, String outlineSubject) {
        if (outlineSubject.isBlank() || serverUrl.isBlank() || teamId == null || teamId.isBlank()) {
            return Optional.empty();
        }
        long outlineProviderId = gitProviderRegistry.resolveProviderId("OUTLINE", serverUrl);
        return accountIdentityQuery
                .resolveAccountId(outlineProviderId, outlineSubject, teamId)
                .map(workspaceMembershipQuery::membershipsForAccount)
                .orElseGet(List::of)
                .stream()
                .filter(view -> view.workspaceId() == workspaceId)
                .map(WorkspaceMembershipView::memberId)
                .filter(Objects::nonNull)
                .findFirst();
    }
}
