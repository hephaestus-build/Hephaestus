package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemChecks;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemChecksSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestChecksRecord;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the checks from the record that capture writes, so a practice that waits for them runs on the evidence it will
 * read. Only GitHub's runs are read, so a GitLab merge request reports none: its pipeline is no review evidence yet.
 */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
class PullRequestChecksSource implements ItemChecksSource {
    private final PullRequestRepository pulls;
    private final ScmWorkCapture capture;

    @Override
    public ItemKind kind() {
        return ScmItemEvents.PULL_REQUEST;
    }

    @Override
    public SourceKind source() {
        return PullRequestChecksRecord.KIND;
    }

    /** Runs after the review pinned the item, which checked current access to it. */
    @Override
    @Transactional(readOnly = true)
    public ItemChecks checks(ItemSnapshot pinned, @Nullable UUID eventId) {
        if (pinned.integration().kind() != IntegrationKind.GITHUB) return ItemChecks.NONE_REPORTED;
        var item = pinned.item();
        var checks = eventId != null
                ? PullRequestChecksRecord.retained(
                        capture.occasion(item, eventId).record())
                : pulls.findById(Objects.requireNonNull(item.sourceId()))
                        .map(pull ->
                                PullRequestChecksRecord.current(pull, Objects.requireNonNull(pull.getHeadRefOid())))
                        .orElse(null);
        return checks == null ? ItemChecks.NONE_REPORTED : PullRequestChecksRecord.readiness(checks);
    }
}
