package de.tum.cit.aet.hephaestus.integration.slack;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance person scope and explicitly workspace-bound Slack attribution")
public class SlackPersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final SlackThreadRepository repository;

    @Override
    public Set<String> artifactKinds() {
        return Set.of("chat.conversation_thread");
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        Set<Long> ids = new TreeSet<>();
        for (var identity : person.identities()) {
            if (identity.teamId() != null)
                ids.addAll(
                        repository.findPersonSourceIds(identity.providerId(), identity.subject(), identity.teamId()));
        }
        return List.copyOf(ids);
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String artifactKind, long artifactId) {
        return repository.findPersonSourceIdentities(workspaceId, artifactId).stream()
                .map(row -> new PersonIdentity(row.getProviderId(), row.getSubject(), row.getTeamId()))
                .toList();
    }
}
