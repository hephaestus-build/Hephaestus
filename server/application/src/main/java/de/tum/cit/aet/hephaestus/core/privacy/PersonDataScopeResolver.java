package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope.StoredOwnerOperation;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonEvidenceErasure;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Resolves the subject's identities and derived evidence in the transaction that reads the person's stores. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@StoredOwnerOperation
class PersonDataScopeResolver {
    private final PersonIdentityResolver identities;
    private final List<PersonEvidenceErasure> evidenceStores;
    private final OwnerScope owners;

    PersonScope resolve(@Nullable Long accountId, List<PersonIdentity> subjects) {
        PersonScope person = identities.resolve(accountId, subjects);
        bind(person);
        return withEvidenceJobs(person);
    }

    void bind(PersonScope person) {
        if (person.accountId() != null) owners.ofJob(person.accountId());
    }

    private PersonScope withEvidenceJobs(PersonScope resolved) {
        var derivedJobs = Stream.concat(
                        resolved.derivedJobIds().stream(),
                        evidenceStores.stream().flatMap(store -> store.jobsContaining(resolved).stream()))
                .distinct()
                .sorted()
                .toList();
        return new PersonScope(
                resolved.accountId(),
                resolved.identities(),
                resolved.userIds(),
                resolved.conversationIds(),
                resolved.outlineDocumentIds(),
                resolved.scmArtifactIds(),
                derivedJobs);
    }
}
