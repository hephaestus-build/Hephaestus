package de.tum.cit.aet.hephaestus.integration.scm.github.repository.collaborator;

import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Records that GitHub removed a person's access, so that every monitor of the affected repositories drops the person
 * and a listing that began before the removal cannot name them again. It acts on the ids in the event, not on mirror
 * rows: a listing may have found the person before the mirror stored them.
 */
@Component
@RequiredArgsConstructor
public class GitHubReaderRemovals {

    private final RepositoryReadersProvider readers;
    private final RepositoryRepository repositories;

    /** GitHub removed the person as a collaborator of one repository. */
    public void removedFromRepository(
            long providerId, @Nullable Long readerNativeId, @Nullable Long repositoryNativeId) {
        if (readerNativeId != null && repositoryNativeId != null) {
            readers.recordReaderRemoved(providerId, readerNativeId, List.of(repositoryNativeId));
        }
    }

    /**
     * GitHub removed the person from a team or from the organization. The mirror cannot show which repositories a team
     * reaches, also through a parent team, so the person stops counting as a reader of every repository of the
     * organization. That only narrows: the next listing names them again where they still read.
     */
    public void removedFromOrganization(
            long providerId, @Nullable Long readerNativeId, @Nullable String organizationLogin) {
        if (readerNativeId != null && organizationLogin != null) {
            readers.recordReaderRemoved(
                    providerId,
                    readerNativeId,
                    repositories.findNativeIdsByOrganizationLogin(organizationLogin, providerId));
        }
    }

    /**
     * GitHub removed a team's access to one repository. The mirror cannot show which members the team gave their only
     * access, so the repository's listing stops proving its readers until the next one.
     */
    public void teamAccessRemoved(long providerId, @Nullable Long repositoryNativeId) {
        if (repositoryNativeId != null) {
            readers.recordRepositoryReadersStale(providerId, repositoryNativeId);
        }
    }
}
