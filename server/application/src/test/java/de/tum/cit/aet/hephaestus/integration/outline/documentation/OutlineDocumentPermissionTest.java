package de.tum.cit.aet.hephaestus.integration.outline.documentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SourceReadAudience;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class OutlineDocumentPermissionTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 7L;
    private static final long DOCUMENT_ID = 19L;
    private static final long CONNECTION_ID = 21L;
    private static final long PROVIDER_ID = 31L;
    private static final String SERVER_URL = "https://WIKI.example.com:443/";
    private static final String TEAM = "team-a";
    private final OutlineDocumentRepository documents = mock(OutlineDocumentRepository.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final PersonIdentityResolver identities = mock(PersonIdentityResolver.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final PersonProcessingSuppression suppression = mock(PersonProcessingSuppression.class);
    private final OutlineDocumentPermission permission = new OutlineDocumentPermission(
            documents,
            connections,
            providers,
            identities,
            accounts,
            suppression,
            new OutlineOriginPolicy(Set.of("https://wiki.example.com")));

    private Connection connection(String origin) {
        var workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                TEAM,
                new ConnectionConfig.OutlineConfig(origin, null, null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", CONNECTION_ID);
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        return connection;
    }

    private void readableHeader(Connection connection) {
        when(documents.findAccess(WORKSPACE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(new OutlineDocumentAccess(
                        WORKSPACE_ID,
                        DOCUMENT_ID,
                        CONNECTION_ID,
                        "creator",
                        "editor",
                        List.of("collaborator", "creator"),
                        null)));
        when(connections.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection));
        when(providers.findProviderId("OUTLINE", SERVER_URL)).thenReturn(Optional.of(PROVIDER_ID));
    }

    @Test
    void readsEveryDistinctContributorFromTheActualConnectionWithoutReadingBodyOrCreatingAuthority() {
        readableHeader(connection(SERVER_URL));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isTrue();
        assertThat(permission.identities(WORKSPACE_ID, DOCUMENT_ID))
                .containsExactly(
                        new PersonIdentity(PROVIDER_ID, "creator", TEAM),
                        new PersonIdentity(PROVIDER_ID, "editor", TEAM),
                        new PersonIdentity(PROVIDER_ID, "collaborator", TEAM));
        for (String subject : List.of("creator", "editor", "collaborator"))
            verify(identities).accountFor(new PersonIdentity(PROVIDER_ID, subject, TEAM));
        verify(documents, never()).findByWorkspaceIdAndIdForProjection(WORKSPACE_ID, DOCUMENT_ID);
        verify(connections, never()).findActive(WORKSPACE_ID, IntegrationKind.OUTLINE);
        verify(providers, never()).resolveProviderId("OUTLINE", SERVER_URL);
        verifyNoInteractions(accounts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"creator", "editor", "collaborator"})
    void refusesKnownNoAiEvenWhenItsHistoricalChoiceTimestampIsUnknown(String subject) {
        readableHeader(connection(SERVER_URL));
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, subject, TEAM)))
                .thenReturn(Optional.of(51L));
        when(accounts.find(51L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        51L, Account.Status.ACTIVE, "Person", AiChoice.NO_AI, null, true)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
    }

    @Test
    void permitsOnlyTheHumanRecordedStandingPastAContributorWhoChoseNoAi() {
        readableHeader(connection(SERVER_URL));
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, "editor", TEAM)))
                .thenReturn(Optional.of(51L));
        when(accounts.find(51L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        51L, Account.Status.ACTIVE, "Person", AiChoice.NO_AI, null, true)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, SourceReadAudience.PROCESSING))
                .isFalse();
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, SourceReadAudience.HUMAN_RECORDED_STANDING))
                .isTrue();
    }

    @Test
    void keepsEveryOtherDocumentControlForTheHumanRecordedStanding() {
        var human = SourceReadAudience.HUMAN_RECORDED_STANDING;
        readableHeader(connection(SERVER_URL));
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, "editor", TEAM)))
                .thenReturn(Optional.of(51L));
        when(accounts.find(51L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        51L, Account.Status.BLOCKED, "Person", AiChoice.NO_AI, null, true)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();
        when(accounts.find(51L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(
                        51L, Account.Status.ACTIVE, "Person", AiChoice.NO_AI, null, true)));
        when(suppression.isAccountSuppressed(51L)).thenReturn(true);
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();
        when(suppression.isAccountSuppressed(51L)).thenReturn(false);
        when(suppression.isSuppressed(PROVIDER_ID, "collaborator", TEAM)).thenReturn(true);
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();
        when(suppression.isSuppressed(PROVIDER_ID, "collaborator", TEAM)).thenReturn(false);
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, "creator", TEAM)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Ambiguous ownership"));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();

        var retired = connection(SERVER_URL);
        ConnectionFixtures.reachState(retired, IntegrationState.UNINSTALLED);
        when(connections.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(retired));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();
        when(connections.findInWorkspace(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(Optional.of(connection("https://unapproved.example.com")));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();
        when(documents.findAccess(WORKSPACE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(
                        new OutlineDocumentAccess(8L, DOCUMENT_ID, CONNECTION_ID, "creator", null, null, null)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID, human)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = Account.Status.class,
            names = {"BLOCKED", "DELETING", "DELETED"})
    void refusesAccountsThatCannotProcessWork(Account.Status status) {
        readableHeader(connection(SERVER_URL));
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, "creator", TEAM)))
                .thenReturn(Optional.of(51L));
        when(accounts.find(51L))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(51L, status, "Person", null, null, true)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
    }

    @Test
    void refusesASuppressedNativeContributorWithoutAnAccountLink() {
        readableHeader(connection(SERVER_URL));
        when(suppression.isSuppressed(PROVIDER_ID, "collaborator", TEAM)).thenReturn(true);
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
    }

    @Test
    void refusesKnownAccountSuppressionAndMissingAccount() {
        readableHeader(connection(SERVER_URL));
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, "creator", TEAM)))
                .thenReturn(Optional.of(51L));
        when(suppression.isAccountSuppressed(51L)).thenReturn(true);
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        when(suppression.isAccountSuppressed(51L)).thenReturn(false);
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        when(accounts.find(51L))
                .thenReturn(Optional.of(
                        new AccountDirectory.AccountView(51L, Account.Status.ACTIVE, "Person", null, null, true)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isTrue();
    }

    @Test
    void refusesAmbiguousRetainedAccountOwnership() {
        readableHeader(connection(SERVER_URL));
        when(identities.accountFor(new PersonIdentity(PROVIDER_ID, "editor", TEAM)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Ambiguous ownership"));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
    }

    @Test
    void preservesIdentityAttributionWhenTheDocumentConnectionIsRetired() {
        var connection = connection(SERVER_URL);
        ConnectionFixtures.reachState(connection, IntegrationState.UNINSTALLED);
        readableHeader(connection);
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        assertThat(permission.identities(WORKSPACE_ID, DOCUMENT_ID)).hasSize(3);
        verifyNoInteractions(identities, accounts);
    }

    @Test
    void refusesTheActualUnapprovedConnectionEvenIfAnotherWorkspaceConnectionIsApproved() {
        when(documents.findAccess(WORKSPACE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(
                        new OutlineDocumentAccess(WORKSPACE_ID, DOCUMENT_ID, CONNECTION_ID, null, null, null, null)));
        when(connections.findInWorkspace(WORKSPACE_ID, CONNECTION_ID))
                .thenReturn(Optional.of(connection("https://unapproved.example.com")));
        when(providers.findProviderId("OUTLINE", "https://unapproved.example.com"))
                .thenReturn(Optional.of(PROVIDER_ID));
        when(connections.findActiveConnections(WORKSPACE_ID, IntegrationKind.OUTLINE))
                .thenReturn(List.of(connection(SERVER_URL), connection("https://unapproved.example.com")));
        assertThat(permission.workspaceReadable(WORKSPACE_ID)).isTrue();
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        verifyNoInteractions(identities, accounts);
    }

    @Test
    void refusesMissingSourceForeignHeaderAndMissingProviderWithoutLookingUpPeople() {
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        when(documents.findAccess(WORKSPACE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(
                        new OutlineDocumentAccess(8L, DOCUMENT_ID, CONNECTION_ID, "creator", null, null, null)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        verifyNoInteractions(connections, providers, identities, accounts);
        when(documents.findAccess(WORKSPACE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(new OutlineDocumentAccess(
                        WORKSPACE_ID, DOCUMENT_ID, CONNECTION_ID, "creator", null, null, null)));
        when(connections.findInWorkspace(WORKSPACE_ID, CONNECTION_ID)).thenReturn(Optional.of(connection(SERVER_URL)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isFalse();
        verifyNoInteractions(identities, accounts);
    }

    @Test
    void permitsRetainedDeletedMetadataWithoutGrantingCurrentBodyAuthority() {
        readableHeader(connection(SERVER_URL));
        when(documents.findAccess(WORKSPACE_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(new OutlineDocumentAccess(
                        WORKSPACE_ID,
                        DOCUMENT_ID,
                        CONNECTION_ID,
                        "creator",
                        "editor",
                        List.of("collaborator"),
                        Instant.EPOCH)));
        assertThat(permission.permits(WORKSPACE_ID, DOCUMENT_ID)).isTrue();
        verify(documents, never()).findByWorkspaceIdAndIdForProjection(WORKSPACE_ID, DOCUMENT_ID);
    }
}
