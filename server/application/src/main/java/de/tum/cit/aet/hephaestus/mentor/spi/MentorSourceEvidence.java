package de.tum.cit.aet.hephaestus.mentor.spi;

/**
 * The provider store's own record of a question, read before each dispatch. Intake can miss a delete that its retries
 * never admitted, so the store's tombstone is the second witness. An unwired port refuses every dispatch.
 */
public interface MentorSourceEvidence {
    /**
     * In the caller's transaction, database only: whether the provider store shows the message deleted. It holds the
     * stored row against a concurrent tombstone until the caller commits.
     */
    boolean deleted(long workspaceId, String containerId, String nativeMessageId);
}
