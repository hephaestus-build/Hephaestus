package de.tum.cit.aet.hephaestus.integration.outline;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentPermission;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@WorkspaceAgnostic("Instance person scope and explicitly workspace-bound Outline attribution")
public class OutlinePersonSourceIdentityContributor implements PersonSourceIdentityContributor {
    private final OutlineDocumentRepository repository;
    // Suppression collects source contributors; initialize permission only for exact attribution reads.
    private final ObjectProvider<OutlineDocumentPermission> permission;

    @Override
    public PersonSourceScope scope() {
        return PersonSourceScope.DOCUMENTS;
    }

    @Override
    public Set<String> itemKinds() {
        return Set.of(DocumentEvents.DOCUMENT.value());
    }

    @Override
    public List<Long> sourceIds(PersonScope person) {
        Set<Long> ids = new TreeSet<>();
        for (var identity : person.identities())
            ids.addAll(repository.findPersonSourceIds(
                    identity.providerId(), identity.subject(), Objects.requireNonNullElse(identity.teamId(), "")));
        return List.copyOf(ids);
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String itemKind, long sourceId) {
        return permission.getObject().identities(workspaceId, sourceId);
    }
}
