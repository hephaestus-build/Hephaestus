package de.tum.cit.aet.hephaestus.practices.spi;

/** Erase the practice results derived from a workspace's mirrored documents, and their feedback. */
public interface DocumentFeedbackErasure {
    int eraseAllDocumentsForWorkspace(long workspaceId);
}
