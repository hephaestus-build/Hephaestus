package de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitAuthorResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitLabCommitSyncServiceTest extends BaseUnitTest {

    /** A repository with no new commits answers its first page empty: the read reached the end. */
    @Test
    void shouldReportCompletedWhenTheFirstCommitPageIsEmpty() {
        var tokens = mock(GitLabTokenService.class);
        when(tokens.resolveServerUrl(1L)).thenReturn("https://gitlab.example.com");
        when(tokens.getAccessToken(1L)).thenReturn("token");
        var admission = mock(GitLabProjectAdmission.class);
        when(admission.current(any())).thenReturn(true);
        var empty = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("[]")
                        .build()));
        var service = new GitLabCommitSyncService(
                mock(CommitRepository.class),
                mock(CommitContributorRepository.class),
                mock(CommitAuthorResolver.class),
                tokens,
                event -> {},
                () -> empty,
                admission);

        SyncResult result = service.syncCommitsForRepository(
                1L, repository(), null, mock(GitLabProjectAdmission.ProjectRead.class));

        assertThat(result.status()).isEqualTo(SyncResult.Status.COMPLETED);
    }

    private static Repository repository() {
        var provider = new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com");
        provider.setId(2L);
        var repository = new Repository();
        repository.setNameWithOwner("group/project");
        repository.setDefaultBranch("main");
        repository.setNativeId(7L);
        repository.setProvider(provider);
        return repository;
    }
}
