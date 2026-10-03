package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;

/** READ_COMMITTED transaction admission for exact native identities; a lock is not an identity match. */
public interface PersonDataWriteFence {
    /** Hold until the content write commits; refuse identities with a permanent processing control. */
    @com.google.errorprone.annotations.CheckReturnValue
    boolean holdForWrite(List<PersonIdentity> identities);

    @com.google.errorprone.annotations.CheckReturnValue
    boolean holdForUserWrite(long userId);

    /** Acquire one ordered lock set, then return only the existing, non-suppressed users. */
    @com.google.errorprone.annotations.CheckReturnValue
    List<Long> holdForUserWrites(List<Long> userIds);

    /** Finish admitted writers before checking the preview and installing permanent controls. */
    void holdForErasure(List<PersonIdentity> identities);
}
