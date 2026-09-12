package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record CommitDetails(
        String sha,
        String message,
        @Nullable String messageBody,
        String authorName,
        String authorEmail,
        Instant authoredAt,
        String committerName,
        String committerEmail,
        Instant committedAt,
        int additions,
        int deletions,
        int changedFiles,
        List<FileChange> fileChanges,
        List<String> parentShas) {

    public record FileChange(
            String filename,
            ChangeType changeType,
            int additions,
            int deletions,
            int changes,
            @Nullable String previousFilename) {}
}
