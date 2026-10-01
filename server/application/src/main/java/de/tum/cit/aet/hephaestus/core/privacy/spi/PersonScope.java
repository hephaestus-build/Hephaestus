package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Verified identity closure, never inferred from names, logins, email or cached actor ids. */
public record PersonScope(
        @Nullable Long accountId,
        List<PersonIdentity> identities,
        List<Long> userIds,
        List<Long> conversationIds,
        List<Long> outlineDocumentIds,
        List<Long> scmArtifactIds) {
    public PersonScope(@Nullable Long accountId, List<PersonIdentity> identities, List<Long> userIds) {
        this(accountId, identities, userIds, List.of(), List.of(), List.of());
    }

    public PersonScope {
        identities = List.copyOf(identities);
        userIds = List.copyOf(userIds);
        conversationIds = List.copyOf(conversationIds);
        outlineDocumentIds = List.copyOf(outlineDocumentIds);
        scmArtifactIds = List.copyOf(scmArtifactIds);
    }
}
