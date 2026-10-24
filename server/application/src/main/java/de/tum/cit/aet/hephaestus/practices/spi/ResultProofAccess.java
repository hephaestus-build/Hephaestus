package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public interface ResultProofAccess {
    /** Whether {@code purpose} may read retained history at all: operator review and practice feedback delivery. */
    static boolean readsHistory(SourceUsePurpose purpose) {
        return purpose == SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW
                || purpose == SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;
    }

    /**
     * The ids of the loaded {@code rows} whose retained history {@code purpose} may read, checked together. Human
     * access to retained history does not certify it for reuse or new delivery.
     */
    Set<UUID> permitsAllHistory(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose);

    /** The ids of the loaded {@code rows} whose current proof {@code purpose} may use, checked together. */
    Set<UUID> permitsAll(long workspaceId, Collection<Result> rows, SourceUsePurpose purpose);

    /**
     * Runs {@code read} as one read of the workspace: every check inside it shares its lookups, so a work item, source,
     * person, account or capture job that several checks reach is looked up once. Changes no answer.
     */
    <T> T asOneRead(long workspaceId, Supplier<T> read);
}
