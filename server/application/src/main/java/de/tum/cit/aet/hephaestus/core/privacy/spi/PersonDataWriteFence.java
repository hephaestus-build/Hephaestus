package de.tum.cit.aet.hephaestus.core.privacy.spi;

import com.google.errorprone.annotations.CheckReturnValue;
import java.util.List;

/**
 * READ_COMMITTED transaction admission for exact native identities; a lock is not an identity
 * match.
 */
public interface PersonDataWriteFence {
    /**
     * Hold until the content write commits; refuse identities with a permanent processing control.
     */
    @CheckReturnValue
    boolean holdForWrite(List<PersonIdentity> identities);

    @CheckReturnValue
    boolean holdForAccountWrite(long accountId);

    /** Finish admitted writers before checking the preview and installing permanent controls. */
    void holdForErasure(List<PersonIdentity> identities);
}
