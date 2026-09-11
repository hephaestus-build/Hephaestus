package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class GitLabPushMessageHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GitLabPushMessageHandler handler;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldProcessPushWhenExistingOrganizationIsLazyOutsidePreparationTransaction() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")
                .orElseGet(() ->
                        providers.save(new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de")));
        var organization = new Organization();
        organization.setNativeId(1L);
        organization.setLogin("hephaestustest");
        organization.setName("HephaestusTest");
        organization.setAvatarUrl("");
        organization.setHtmlUrl("https://gitlab.lrz.de/hephaestustest");
        organization.setCreatedAt(Instant.now());
        organization.setUpdatedAt(Instant.now());
        organization.setProvider(provider);
        organization = organizations.save(organization);

        var repository = new Repository();
        repository.setNativeId(246765L);
        repository.setName("demo-repository");
        repository.setNameWithOwner("hephaestustest/demo-repository");
        repository.setHtmlUrl("https://gitlab.lrz.de/hephaestustest/demo-repository");
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setOrganization(organization);
        repository.setProvider(provider);
        Long repositoryId = repositories.save(repository).getId();
        Long providerId = Objects.requireNonNull(provider.getId());
        var detached = Objects.requireNonNull(transactions.execute(status ->
                repositories.findByNativeIdAndProviderId(246765L, providerId).orElseThrow()));
        assertThat(Hibernate.isInitialized(detached.getOrganization())).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();

        GitLabPushEventDTO event;
        try (var input = new ClassPathResource("gitlab/push.json").getInputStream()) {
            event = objectMapper.readValue(input, GitLabPushEventDTO.class);
        }
        handler.handleEvent(event);

        assertThat(commits.findByShaAndRepositoryId("9c5dedd52046bb5213189afc25f75e608a98d462", repositoryId))
                .isPresent();
        assertThat(commits.findByShaAndRepositoryId("a4bf10d93a2d136f1db911b6f1c03d26d835a44f", repositoryId))
                .isPresent();
    }
}
