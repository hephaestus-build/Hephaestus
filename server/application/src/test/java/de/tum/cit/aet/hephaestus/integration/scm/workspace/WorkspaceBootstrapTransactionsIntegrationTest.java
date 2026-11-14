package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SelectedRepositoryVerifier;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceProperties;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSlugService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class WorkspaceBootstrapTransactionsIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionService connections;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldReadProviderIdentityOutsideTransactionsThenCommitTheWorkspaceAndItsCredentialTogether() {
        String group = "bootstrap-" + UUID.randomUUID();
        long nativeId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        var calls = new AtomicInteger();
        var service = service(group, nativeId, connections, calls);
        service.bootstrapDefaultGitLabPatWorkspace();
        var workspace = workspaces.findAllByAccountLoginIgnoreCase(group).stream()
                .findFirst()
                .orElseThrow();
        var config = connections.findActiveGitLabConfig(workspace.getId()).orElseThrow();
        assertThat(config.gitlabGroupId()).isEqualTo(42L);
        assertThat(connections
                        .findActiveBearerToken(workspace.getId(), IntegrationKind.GITLAB)
                        .orElseThrow()
                        .token())
                .isEqualTo("bootstrap-token");
        assertThat(jdbc.queryForObject(
                        "select count(*) from \"user\" where native_id = ? and login = ?", Long.class, nativeId, group))
                .isEqualTo(1L);
        assertThat(calls).hasValue(2);
        service.bootstrapDefaultGitLabPatWorkspace();
        assertThat(calls).hasValue(2);
        assertThat(workspaces.findAllByAccountLoginIgnoreCase(group))
                .extracting(w -> w.getId())
                .containsExactly(workspace.getId());
    }

    @Test
    void shouldRollBackPreparedUserAndWorkspaceWhenTheConnectionWriteFails() {
        String group = "rollback-" + UUID.randomUUID();
        long nativeId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
        var refused = mock(ConnectionService.class);
        doThrow(new IllegalStateException("write refused"))
                .when(refused)
                .provisionPatConnection(any(), any(), any(), any(), any(), any());
        var service = service(group, nativeId, refused, new AtomicInteger());
        assertThatThrownBy(service::bootstrapDefaultGitLabPatWorkspace)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("write refused");
        assertThat(workspaces.findAllByAccountLoginIgnoreCase(group)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select count(*) from \"user\" where native_id = ? and login = ?", Long.class, nativeId, group))
                .isZero();
    }

    @Test
    void shouldRefuseAnAmbientBootstrapTransactionBeforeProviderIo() {
        String group = "ambient-" + UUID.randomUUID();
        var calls = new AtomicInteger();
        var service = service(group, 123L, connections, calls);
        new TransactionTemplate(transactions)
                .executeWithoutResult(status -> assertThatThrownBy(service::bootstrapDefaultGitLabPatWorkspace)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("cannot hold a transaction"));
        assertThat(calls).hasValue(0);
        assertThat(workspaces.findAllByAccountLoginIgnoreCase(group)).isEmpty();
    }

    private WorkspaceProvisioningService service(
            String group, long nativeId, ConnectionService owner, AtomicInteger calls) {
        String origin = "https://gitlab.com";
        var endpoints = mock(ScmServerEndpointPolicy.class);
        var client = WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    assertThat(workspaces.findAllByAccountLoginIgnoreCase(group))
                            .isEmpty();
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"id\":" + nativeId + ",\"username\":\"" + group
                                    + "\",\"name\":\"Bootstrap owner\",\"bot\":false}")
                            .build());
                })
                .build();
        when(endpoints.clientFor(origin)).thenReturn(client);
        var verifier = mock(SelectedRepositoryVerifier.class);
        when(verifier.kind()).thenReturn(IntegrationKind.GITLAB);
        when(verifier.resolveAccountId("bootstrap-token", origin, group)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            calls.incrementAndGet();
            return 42L;
        });
        var settings = new WorkspaceProperties(
                false,
                null,
                true,
                new WorkspaceProperties.GitLabDefaultProperties(group, "bootstrap-token", origin),
                null);
        return new WorkspaceProvisioningService(
                settings,
                workspaces,
                context.getBean(RepositoryToMonitorRepository.class),
                context.getBean(WorkspaceService.class),
                context.getBean(WorkspaceSlugService.class),
                context.getBean(UserRepository.class),
                context.getBean(IdentityProviderLookup.class),
                context.getBean(SourceProviderNamespaces.class),
                context.getBean(MembershipRepository.class),
                context.getBean(MembershipService.class),
                owner,
                List.of(),
                endpoints,
                context.getBean(WebClient.Builder.class),
                List.of(verifier),
                List.of(),
                transactions);
    }
}
