package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.List;

/** Keep departed subjects in directory captures until downstream managed-access removal is confirmed. */
public interface DirectorySubjectRetention {
    List<String> retainedSubjects(long workspaceId, long providerId);
}
