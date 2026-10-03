package de.tum.cit.aet.hephaestus.integration.outline;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance person scope and explicitly workspace-bound Outline attribution")
public class OutlinePersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final OutlineDocumentRepository repository;

    @Override
    public Set<String> artifactKinds() {
        return Set.of("docs.document");
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        Set<Long> ids = new TreeSet<>();
        for (var identity : person.identities())
            ids.addAll(repository.findPersonSourceIds(identity.providerId(), identity.subject()));
        return List.copyOf(ids);
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String artifactKind, long artifactId) {
        return repository.findPersonSourceIdentities(workspaceId, artifactId).stream()
                .map(row -> new PersonIdentity(row.getProviderId(), row.getSubject(), null))
                .toList();
    }
}
