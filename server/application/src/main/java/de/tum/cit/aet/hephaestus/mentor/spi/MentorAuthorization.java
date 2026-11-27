package de.tum.cit.aet.hephaestus.mentor.spi;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Captures and rechecks local authority for one verified mentor audience in the caller's transaction.
 * An unwired port denies admission. No operation calls a provider.
 */
public interface MentorAuthorization {
    /** Uses the verified app principal and the work scope selected by policy. */
    Optional<ScopeStamp> captureApp(long verifiedAccountId, Audience audience, @Nullable Long workWorkspaceId);

    /** Uses the verified ingress envelope. An unlinked actor or inconsistent binding cannot open a request. */
    Optional<ScopeStamp> captureSlack(VerifiedActor actor, Binding binding, Audience audience);

    /**
     * Rechecks the stored stamp and audience under their owner scope in the caller's transaction. Holds the same
     * database guards that the revocation writers use until that transaction commits. This check calls no provider.
     */
    boolean isCurrent(ScopeStamp stamp, Audience audience);
}
