package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabProjectEventDTO;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionTemplate;

class GitLabProjectEventMessageHandlerTest extends BaseUnitTest {
    @ParameterizedTest
    @CsvSource({
        "project_update,private,PRIVATE,true",
        "project_update,internal,INTERNAL,false",
        "project_update,public,PUBLIC,false",
        "project_rename,private,PRIVATE,true",
        "project_transfer,internal,INTERNAL,false",
        "project_create,public,PUBLIC,false"
    })
    void shouldRefreshBothVisibilityFieldsWhenProjectAttributesChange(
            String event, String visibility, Repository.Visibility expected, boolean privateRepository) {
        var repositories = mock(RepositoryRepository.class);
        var providers = mock(IdentityProviderRepository.class);
        var properties = mock(GitLabProperties.class);
        var provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example");
        provider.setId(2L);
        var repository = new Repository();
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setNameWithOwner("group/project");
        when(properties.defaultServerUrl()).thenReturn("https://gitlab.example");
        when(providers.findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example"))
                .thenReturn(Optional.of(provider));
        when(repositories.findByNativeIdAndProviderId(12L, 2L))
                .thenReturn(event.equals("project_create") ? Optional.empty() : Optional.of(repository));
        when(repositories.save(any(Repository.class))).thenAnswer(invocation -> {
            Repository saved = invocation.getArgument(0);
            assertThat(saved.getVisibility()).isEqualTo(expected);
            assertThat(saved.isPrivate()).isEqualTo(privateRepository);
            return saved;
        });
        var handler = new GitLabProjectEventMessageHandler(
                repositories,
                mock(OrganizationRepository.class),
                providers,
                properties,
                mock(NatsMessageDeserializer.class),
                new TransactionTemplate());
        handler.handleEvent(new GitLabProjectEventDTO(
                event, "project", "project", "group/project", null, 12L, visibility, null, null));
        verify(repositories).save(any(Repository.class));
    }
}
