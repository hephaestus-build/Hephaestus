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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class OutlinePersonSourceIdentityContributor implements PersonSourceIdentityContributor, OutlinePersonCopyScope {
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
        return matchingDocuments(person).stream()
                .map(OutlineDocumentRepository.PersonSourceDocument::getId)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public Selection selectSources(PersonScope person) {
        var rows = matchingDocuments(person);
        Set<Long> frozen = Set.copyOf(person.outlineDocumentIds());
        boolean complete = rows.stream().allMatch(row -> frozen.contains(row.getId()));
        var sourcesByWorkspace = rows.stream()
                .filter(row -> frozen.contains(row.getId()))
                .collect(Collectors.groupingBy(
                        OutlineDocumentRepository.PersonSourceDocument::getWorkspaceId,
                        Collectors.collectingAndThen(
                                Collectors.mapping(
                                        row -> new Source(row.getOrigin(), row.getTeam()), Collectors.toSet()),
                                sources -> sources.stream()
                                        .sorted(Comparator.comparing(Source::origin)
                                                .thenComparing(Source::team))
                                        .toList())));
        return new Selection(sourcesByWorkspace, complete);
    }

    private List<OutlineDocumentRepository.PersonSourceDocument> matchingDocuments(PersonScope person) {
        return person.identities().stream()
                .flatMap(identity ->
                        repository
                                .findPersonSourceIds(identity.providerId(), identity.subject(), identity.teamKey())
                                .stream())
                .toList();
    }

    @Override
    public boolean onlyOutline(PersonScope person) {
        return !person.identities().isEmpty()
                && person.identities().stream()
                        .allMatch(identity -> providers
                                .findById(identity.providerId())
                                .filter(provider -> provider.kind() == IntegrationKind.OUTLINE)
                                .isPresent());
    }

    @Override
    public List<PersonIdentity> identitiesForSource(long workspaceId, String itemKind, long sourceId) {
        return permission.getObject().identities(workspaceId, sourceId);
    }
}
