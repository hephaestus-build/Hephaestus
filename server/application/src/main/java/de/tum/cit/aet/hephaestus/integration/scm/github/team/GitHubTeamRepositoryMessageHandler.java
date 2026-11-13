package de.tum.cit.aet.hephaestus.integration.scm.github.team;

import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.dto.GitHubTeamEventDTO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Routes the repository-tier slice of GitHub {@code team} events.
 *
 * <p>The {@code added_to_repository} and {@code removed_from_repository} actions carry a
 * {@code repository} object, so the generic subject deriver keys them to {@code repository.team} rather
 * than {@code organization.team}. That key had no handler, so those permission-change events were
 * silently ACK-dropped by the consumer and only reconciled at the next full team sync.
 *
 * <p>This handler closes that gap by registering for {@code repository.team} and delegating to the same
 * {@link GitHubTeamMessageHandler#routeTeamEvent(GitHubTeamEventDTO)} dispatch, so both subject tiers
 * share one code path. The two handlers never collide: created/edited/deleted deliveries carry no
 * repository and arrive on {@code organization.team}; added/removed deliveries always carry a repository
 * and arrive here.
 */
@Component
public class GitHubTeamRepositoryMessageHandler extends AbstractIntegrationMessageHandler<GitHubTeamEventDTO> {

    private final GitHubTeamMessageHandler delegate;

    GitHubTeamRepositoryMessageHandler(
            GitHubTeamMessageHandler delegate,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.TEAM.getValue(),
                GitHubTeamEventDTO.class,
                deserializer,
                transactionTemplate);
        this.delegate = delegate;
    }

    @Override
    protected void handleEvent(GitHubTeamEventDTO event) {
        delegate.routeTeamEvent(event);
    }
}
