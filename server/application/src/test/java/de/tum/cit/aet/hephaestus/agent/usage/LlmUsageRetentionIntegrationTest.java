package de.tum.cit.aet.hephaestus.agent.usage;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class LlmUsageRetentionIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private LlmUsageEventRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void deletesOnlyEventsBeforeTheCutoffAcrossWorkspaces() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        Workspace first = workspace("usage-retention-first");
        Workspace second = workspace("usage-retention-second");
        LlmUsageEvent expiredFirst = event(first, cutoff.minusSeconds(1));
        LlmUsageEvent atCutoff = event(first, cutoff);
        LlmUsageEvent afterCutoff = event(first, cutoff.plusSeconds(1));
        LlmUsageEvent expiredSecond = event(second, cutoff.minusSeconds(1));
        repository.saveAllAndFlush(List.of(expiredFirst, atCutoff, afterCutoff, expiredSecond));

        assertThat(deleteExpired(cutoff, 500)).isEqualTo(2);

        assertThat(repository.findAllById(List.of(expiredFirst.getId(), expiredSecond.getId())))
                .isEmpty();
        assertThat(repository.findAllById(List.of(atCutoff.getId(), afterCutoff.getId())))
                .extracting(LlmUsageEvent::getId)
                .containsExactlyInAnyOrder(atCutoff.getId(), afterCutoff.getId());
    }

    @Test
    void deletesNoMoreThanOneBatchPerCall() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        Workspace workspace = workspace("usage-retention-batched");
        LlmUsageEvent first = event(workspace, cutoff.minusSeconds(2));
        LlmUsageEvent second = event(workspace, cutoff.minusSeconds(1));
        repository.saveAllAndFlush(List.of(first, second));

        assertThat(deleteExpired(cutoff, 1)).isEqualTo(1);

        assertThat(repository.findAllById(List.of(first.getId(), second.getId())))
                .hasSize(1);
    }

    private int deleteExpired(Instant cutoff, int batchSize) {
        Integer deleted = transactionTemplate.execute(status -> repository.deleteExpired(cutoff, batchSize));
        return deleted != null ? deleted : 0;
    }

    private Workspace workspace(String slug) {
        User owner = persistUser(slug + "-owner");
        return createWorkspace(slug, "Usage retention", slug + "-org", AccountType.ORG, owner);
    }

    private LlmUsageEvent event(Workspace workspace, Instant occurredAt) {
        LlmUsageEvent event = new LlmUsageEvent();
        event.setId(UUID.randomUUID());
        event.setWorkspaceId(workspace.getId());
        event.setJobType("PRACTICE_REVIEW");
        event.setSourceId(UUID.randomUUID());
        event.setSourceType(UsageSourceType.REVIEW_JOB);
        event.setOccurredAt(occurredAt);
        event.setStartedAt(event.getOccurredAt());
        return event;
    }
}
