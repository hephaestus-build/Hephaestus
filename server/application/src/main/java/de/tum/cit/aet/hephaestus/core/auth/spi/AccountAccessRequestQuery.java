package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Account export's explicit allowlist of the applicant's own workspace access submissions. */
public interface AccountAccessRequestQuery {
    List<Submission> submissionsForAccount(Long accountId);

    record Submission(
            String workspaceSlug,
            Instant submittedAt,
            Instant requestedExpiry,
            List<Long> requestedTeamIds,
            @Nullable String comments,
            String acknowledgedIntroduction,
            String acknowledgementLabel,
            List<Notice> acknowledgedNotices) {}

    record Notice(String key, String title, String markdown) {}
}
