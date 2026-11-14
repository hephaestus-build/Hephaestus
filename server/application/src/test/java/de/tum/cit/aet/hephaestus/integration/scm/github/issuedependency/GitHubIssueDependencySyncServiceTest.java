package de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceNamespace;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncMetadata;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubExceptionClassifier;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlSyncCoordinator;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubSyncProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("unit")
class GitHubIssueDependencySyncServiceTest {

    @Test
    void shouldFailBeforeTransportAndTimestampWhenCanonicalGitHubProviderIsMissing() {
        var providers = mock(IdentityProviderLookup.class);
        var repositories = mock(RepositoryRepository.class);
        var targets = mock(SyncTargetProvider.class);
        var clients = mock(GitHubGraphQlClientProvider.class);
        when(targets.getSyncMetadata(1L))
                .thenReturn(Optional.of(new SyncMetadata(1L, "Workspace", "owner", null, null, null, null)));
        when(targets.getRepositoryNamesForScope(1L)).thenReturn(List.of("owner/repo"));
        when(providers.findId(IdentityProviderType.GITHUB, PersonSourceNamespace.GITHUB_DEFAULT_URL))
                .thenReturn(Optional.empty());
        var service = new GitHubIssueDependencySyncService(
                mock(IssueRepository.class),
                repositories,
                providers,
                targets,
                clients,
                mock(GitHubSyncProperties.class),
                mock(GitHubExceptionClassifier.class),
                mock(SyncSchedulerProperties.class),
                mock(GitHubIssueProcessor.class),
                mock(PlatformTransactionManager.class),
                mock(GitHubGraphQlSyncCoordinator.class));

        assertThatIllegalStateException()
                .isThrownBy(() -> service.syncDependenciesForScope(1L))
                .withMessage("IdentityProvider not found for type=GITHUB, serverUrl=https://github.com");

        verifyNoInteractions(clients, repositories);
        verify(targets, never()).updateScopeSyncTimestamp(anyLong(), any(), any());
    }
}
