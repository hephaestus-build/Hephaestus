package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.dto.GitLabPushEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** A tag changes the checkout's readable refs even when the reviewed head stays the same. */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabTagPushMessageHandler extends AbstractIntegrationMessageHandler<GitLabPushEventDTO> {
    private final RepositoryRepository repositories;
    private final GitLabRouteAdmission routes;
    private final RepositoryRefState refs;

    public GitLabTagPushMessageHandler(
            RepositoryRepository repositories,
            GitLabRouteAdmission routes,
            RepositoryRefState refs,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactions) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.TAG_PUSH.getValue(),
                GitLabPushEventDTO.class,
                deserializer,
                transactions);
        this.repositories = repositories;
        this.routes = routes;
        this.refs = refs;
    }

    @Override
    protected void handleEvent(GitLabPushEventDTO event) {
        var route = GitLabRouteAdmission.current();
        var project = event.project();
        if (route.isEmpty()
                || project == null
                || project.pathWithNamespace() == null
                || !route.orElseThrow().contains(project.pathWithNamespace())) return;
        var admitted = route.orElseThrow();
        repositories
                .findByNameWithOwnerAndProviderId(project.pathWithNamespace(), admitted.providerId())
                .filter(repository -> routes.admitRepository(admitted, repository))
                .ifPresent(repository -> refs.invalidate(repository.getNativeId(), admitted.providerId()));
    }
}
