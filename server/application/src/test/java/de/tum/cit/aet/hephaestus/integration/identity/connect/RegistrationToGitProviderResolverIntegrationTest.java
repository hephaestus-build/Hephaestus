package de.tum.cit.aet.hephaestus.integration.identity.connect;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

class RegistrationToGitProviderResolverIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private RegistrationToGitProviderResolver resolver;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void shouldCommitTheNewProviderBeforeAnotherProvisioningTransactionReadsIt() {
        String origin = "https://committed-" + UUID.randomUUID() + ".example.test";
        var outer = new TransactionTemplate(transactionManager);
        var reader = new TransactionTemplate(transactionManager);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Long id = outer.execute(status -> {
            long created = resolver.resolveProviderId("GITLAB", origin + "/path");
            var visible = reader.execute(read -> providers.existsById(created));
            assertThat(visible).isTrue();
            status.setRollbackOnly();
            return created;
        });
        assertThat(providers.findById(Objects.requireNonNull(id)))
                .hasValueSatisfying(
                        provider -> assertThat(provider.getServerUrl()).isEqualTo(origin));
    }

    @Test
    @Transactional
    void shouldBindSourceVisibilityToTheWorkspaceAndActiveConnection() {
        String suffix = UUID.randomUUID().toString();
        String origin = "https://visible-" + suffix + ".example.test";
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.OUTLINE, origin));
        var wrongKind = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, origin));
        var mine = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("visible-" + suffix));
        var other = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("hidden-" + suffix));
        var connection = new Connection(
                mine,
                IntegrationKind.OUTLINE,
                "team-" + suffix,
                new ConnectionConfig.OutlineConfig(origin + "/", null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connection = connections.saveAndFlush(connection);
        long id = Objects.requireNonNull(provider.getId());
        assertThat(resolver.visibleInWorkspace(id, mine.getId())).isTrue();
        assertThat(resolver.visibleInWorkspace(id, other.getId())).isFalse();
        assertThat(resolver.visibleInWorkspace(Objects.requireNonNull(wrongKind.getId()), mine.getId()))
                .isFalse();
        connection.setState(IntegrationState.SUSPENDED);
        connections.saveAndFlush(connection);
        assertThat(resolver.visibleInWorkspace(id, mine.getId())).isFalse();
    }

    @Test
    void shouldReuseEquivalentProviderOriginsDuringIndependentRegistration() {
        String host = "origin-" + UUID.randomUUID() + ".example.test";
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "HTTPS://" + host + ":443/"));
        long id = Objects.requireNonNull(provider.getId());
        assertThat(resolver.findProviderId("GITLAB", "https://" + host + "/path"))
                .contains(id);
        assertThat(resolver.findProviderId("GITHUB", "https://" + host)).isEmpty();
        assertThat(resolver.resolveProviderId("GITLAB", "https://" + host)).isEqualTo(id);
        assertThat(resolver.providerServerUrl(id)).isEqualTo("https://" + host);
    }

    @Test
    @Transactional
    void shouldBatchOnlyExactProviderSubjectAndUnscopedTeamMatches() {
        String suffix = UUID.randomUUID().toString();
        var first = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://first-" + suffix + ".example.test"));
        var second = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://second-" + suffix + ".example.test"));
        var firstUser = users.saveAndFlush(TestUserFactory.createUser(7, "batch-first-" + suffix, first));
        var secondUser = users.saveAndFlush(TestUserFactory.createUser(8, "batch-second-" + suffix, second));
        users.saveAndFlush(TestUserFactory.createUser(8, "batch-unrequested-" + suffix, first));
        var firstKey = new GitProviderRegistry.IdentityKey(Objects.requireNonNull(first.getId()), "7", "");
        var secondKey = new GitProviderRegistry.IdentityKey(Objects.requireNonNull(second.getId()), "8", "");
        var profiles = resolver.profiles(List.of(
                firstKey,
                secondKey,
                new GitProviderRegistry.IdentityKey(firstKey.providerId(), "7", "team"),
                new GitProviderRegistry.IdentityKey(firstKey.providerId(), "U7", ""),
                new GitProviderRegistry.IdentityKey(firstKey.providerId(), "99", "")));
        assertThat(profiles).containsOnlyKeys(firstKey, secondKey);
        assertThat(profiles.get(firstKey))
                .isEqualTo(new GitProviderRegistry.IdentityProfile(
                        firstUser.getLogin(), firstUser.getName(), firstUser.getAvatarUrl(), firstUser.getHtmlUrl()));
        assertThat(profiles.get(secondKey))
                .isEqualTo(new GitProviderRegistry.IdentityProfile(
                        secondUser.getLogin(),
                        secondUser.getName(),
                        secondUser.getAvatarUrl(),
                        secondUser.getHtmlUrl()));
        assertThat(resolver.profiles(List.of())).isEmpty();
    }
}
