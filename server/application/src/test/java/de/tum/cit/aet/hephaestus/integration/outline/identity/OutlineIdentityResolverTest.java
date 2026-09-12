package de.tum.cit.aet.hephaestus.integration.outline.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class OutlineIdentityResolverTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long PROVIDER_ID = 17L;
    private static final long ACCOUNT_ID = 7L;
    private static final String SERVER_URL = "https://wiki.example.com";
    private static final String TEAM_ID = "9ff8ee7d-team";
    private static final String SUBJECT = "0aa1bb2c-user";

    @Mock
    private GitProviderRegistry gitProviderRegistry;

    @Mock
    private AccountIdentityQuery accountIdentityQuery;

    @Mock
    private AccountWorkspaceMembershipQuery workspaceMembershipQuery;

    private OutlineIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new OutlineIdentityResolver(gitProviderRegistry, accountIdentityQuery, workspaceMembershipQuery);
        lenient()
                .when(gitProviderRegistry.resolveProviderId("OUTLINE", SERVER_URL))
                .thenReturn(PROVIDER_ID);
    }

    private static AccountWorkspaceMembershipQuery.WorkspaceMembershipView membership(
            long workspaceId, @Nullable Long memberId) {
        return new AccountWorkspaceMembershipQuery.WorkspaceMembershipView(
                workspaceId, "ws", "Workspace", "MEMBER", memberId);
    }

    @Test
    @DisplayName("a linked author with membership in the workspace resolves to the view's member id")
    void happyPath_resolvesMemberId() {
        when(accountIdentityQuery.resolveAccountId(PROVIDER_ID, SUBJECT, TEAM_ID))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(workspaceMembershipQuery.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(membership(WORKSPACE_ID, 555L)));

        Optional<Long> memberId = resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, SUBJECT);

        assertThat(memberId).contains(555L);

        verify(accountIdentityQuery).resolveAccountId(PROVIDER_ID, SUBJECT, TEAM_ID);
    }

    @Test
    @DisplayName("an unknown / unlinked Outline subject resolves to empty")
    void unknownSubject_resolvesEmpty() {
        when(accountIdentityQuery.resolveAccountId(PROVIDER_ID, SUBJECT, TEAM_ID))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, SUBJECT))
                .isEmpty();
        verifyNoInteractions(workspaceMembershipQuery);
    }

    @Test
    @DisplayName("membership in a different workspace is filtered out — never a cross-workspace attribution")
    void crossWorkspaceMembership_isFiltered() {
        when(accountIdentityQuery.resolveAccountId(PROVIDER_ID, SUBJECT, TEAM_ID))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(workspaceMembershipQuery.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(membership(WORKSPACE_ID + 1, 555L)));

        assertThat(resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, SUBJECT))
                .isEmpty();
    }

    @Test
    @DisplayName("a membership view without a member id resolves to empty rather than a bogus attribution")
    void membershipWithoutMemberId_resolvesEmpty() {
        when(accountIdentityQuery.resolveAccountId(PROVIDER_ID, SUBJECT, TEAM_ID))
                .thenReturn(Optional.of(ACCOUNT_ID));
        when(workspaceMembershipQuery.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(membership(WORKSPACE_ID, null)));

        assertThat(resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, SUBJECT))
                .isEmpty();
    }

    @Test
    @DisplayName("blank subject or server URL short-circuits to empty without touching the registry")
    void blankInputs_shortCircuit() {
        assertThat(resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, TEAM_ID, " "))
                .isEmpty();
        assertThat(resolver.resolveMemberId(WORKSPACE_ID, "", TEAM_ID, SUBJECT)).isEmpty();
        verifyNoInteractions(gitProviderRegistry);
        verify(accountIdentityQuery, org.mockito.Mockito.never()).resolveAccountId(any(), any(), any());
    }

    @Test
    void shouldRejectMissingTeamBeforeResolvingIdentity() {
        assertThat(resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, null, SUBJECT))
                .isEmpty();
        assertThat(resolver.resolveMemberId(WORKSPACE_ID, SERVER_URL, " ", SUBJECT))
                .isEmpty();
        verifyNoInteractions(gitProviderRegistry, accountIdentityQuery, workspaceMembershipQuery);
    }
}
