package de.tum.cit.aet.hephaestus.integration.outline;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OutlinePersonCopyScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceIdentityContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonSourceScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.outline.documentation.OutlineDocumentPermission;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class OutlinePersonSourceIdentityContributor
        implements PersonSourceIdentityContributor, OutlinePersonCopyScope {
    private final OutlineDocumentRepository repository;
    private final IdentityProviderLookup providers;
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
            ids.addAll(repository.findPersonSourceIds(identity.providerId(), identity.subject(), identity.teamKey()));
        return List.copyOf(ids);
    }

    @Override
    public Map<Long, List<Source>> sources(PersonScope person) {
        if (person.outlineDocumentIds().isEmpty()) return Map.of();
        return repository.findCopySources(person.outlineDocumentIds()).stream()
                .collect(Collectors.groupingBy(
                        OutlineDocumentRepository.CopySourceRow::getWorkspaceId,
                        Collectors.mapping(row -> new Source(row.getOrigin(), row.getTeam()), Collectors.toList())));
    }

    @Override
    public boolean onlyOutline(PersonScope person) {
        return !person.identities().isEmpty() && person.identities().stream()
                .allMatch(identity -> providers.findById(identity.providerId())
                        .filter(provider -> provider.kind() == IntegrationKind.OUTLINE)
                        .isPresent());
    }

    @Override
    public void verify(PersonScope person) {
        if (!Set.copyOf(person.outlineDocumentIds()).containsAll(sourceIds(person))) {
            throw new IllegalStateException("The frozen erasure is missing Outline documents");
        }
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String itemKind, long sourceId) {
        return permission.getObject().identities(workspaceId, sourceId);
    }
}
