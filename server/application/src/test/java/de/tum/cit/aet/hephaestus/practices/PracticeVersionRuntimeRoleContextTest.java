package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class PracticeVersionRuntimeRoleContextTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(
                    PracticeVersionService.class, CatalogOriginPresenter.class, AccountReferenceService.class)
            .withBean(CatalogService.class, () -> mock(CatalogService.class))
            .withBean(PracticeReviewDigest.class, () -> mock(PracticeReviewDigest.class))
            .withBean(WorkspaceReviewDefaultsProvider.class, () -> mock(WorkspaceReviewDefaultsProvider.class))
            .withBean(EntityManagerFactory.class, () -> mock(EntityManagerFactory.class))
            .withBean(PracticeRepository.class, () -> mock(PracticeRepository.class))
            .withBean(PracticeVersionRepository.class, () -> mock(PracticeVersionRepository.class))
            .withBean(PracticeDefinitionValidator.class, () -> mock(PracticeDefinitionValidator.class))
            .withBean(JsonMapper.class, () -> mapper)
            .withBean(AccountDirectory.class, () -> accounts)
            .withBean(AccountIdentityQuery.class, () -> identities)
            .withBean(GitProviderRegistry.class, () -> mock(GitProviderRegistry.class));

    @ParameterizedTest
    @ValueSource(strings = {"server", "none"})
    void shouldReadDefinitionsInEachRuntimeRoleAndResolveCreatorsOnTheServer(String role) {
        context.withPropertyValues("hephaestus.runtime.server.enabled=" + role.equals("server"))
                .run(app -> {
                    assertThat(app).hasNotFailed();
                    var service = app.getBean(PracticeVersionService.class);
                    var definition = DefinitionTestFixtures.definition();
                    var version =
                            TestEntities.practiceVersion(1L, 1, mapper.valueToTree(definition), "v6:" + "b".repeat(64));
                    assertThat(service.definitionOf(version)).isEqualTo(definition);
                    if (role.equals("server")) {
                        var account =
                                new AccountDirectory.AccountView(7L, Account.Status.ACTIVE, "Author", null, null, true);
                        when(accounts.findAll(List.of(7L))).thenReturn(Map.of(7L, account));
                        when(identities.activeLinksForAccounts(List.of(7L))).thenReturn(Map.of());
                        ReflectionTestUtils.setField(version, "createdByAccountId", 7L);
                        var practice = version.getPractice();
                        practice.setId(1L);
                        practice.setCurrentVersion(version);
                        var creator = app.getBean(CatalogOriginPresenter.class)
                                .viewCurrent(practice)
                                .createdBy();
                        assertThat(creator).isNotNull();
                        assertThat(creator.id()).isEqualTo("7");
                    } else {
                        assertThat(app).doesNotHaveBean(AccountReferenceService.class);
                        assertThat(app).doesNotHaveBean(CatalogOriginPresenter.class);
                    }
                });
    }
}
