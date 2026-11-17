package de.tum.cit.aet.hephaestus.practices.model;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultProofAccess;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

@NullMarked
@Tag("unit")
class ResultTest {

    @Test
    void shouldReadMigratedHistoryOnlyAsHistory() {
        Result migrated =
                complete().evidenceVersion(Result.MIGRATED_EVIDENCE_VERSION).build();

        assertThat(migrated.facts().carriesCapturedProof()).isFalse();
        assertThat(migrated.isAdmissibleProof()).isFalse();
        assertThat(ResultProofAccess.readsAsHistory(migrated.facts(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isTrue();
        assertThat(ResultProofAccess.readsAsHistory(migrated.facts(), SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isTrue();
        assertThat(ResultProofAccess.readsAsHistory(migrated.facts(), SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isFalse();
        assertThat(Result.includesEarlierReviews(List.of(complete().build(), migrated)))
                .isTrue();
    }

    @Test
    void shouldAdmitCapturedProofOnlyWithBothPinnedVersions() {
        Result captured = complete().build();

        assertThat(captured.facts().carriesCapturedProof()).isTrue();
        assertThat(captured.isAdmissibleProof()).isTrue();
        assertThat(ResultProofAccess.readsAsHistory(captured.facts(), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
        assertThat(Result.includesEarlierReviews(List.of(captured))).isFalse();

        Result withoutPracticeVersion = complete().practiceVersionId(null).build();
        Result withoutItemVersion = complete().itemVersion(null).build();
        assertThat(withoutPracticeVersion.facts().carriesCapturedProof()).isTrue();
        assertThat(withoutPracticeVersion.isAdmissibleProof()).isFalse();
        assertThat(withoutItemVersion.facts().carriesCapturedProof()).isTrue();
        assertThat(withoutItemVersion.isAdmissibleProof()).isFalse();
    }

    @Test
    void shouldRefuseCapturedProofWithAPartMissing() {
        assertThat(complete().summary(null).build().facts().carriesCapturedProof())
                .isFalse();
        assertThat(complete().explanation(null).build().facts().carriesCapturedProof())
                .isFalse();
        assertThat(complete().evidence(null).build().facts().carriesCapturedProof())
                .isFalse();
        assertThat(complete().coverage(null).build().facts().carriesCapturedProof())
                .isFalse();
    }

    private static Result.ResultBuilder complete() {
        var json = JsonNodeFactory.instance;
        return Result.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .reviewId(UUID.randomUUID())
                .practiceId(2)
                .practiceVersionId(3L)
                .itemId(4)
                .itemVersion("abc123")
                .developerId(5L)
                .outcome(ResultOutcome.NOT_MET)
                .summary("Summary")
                .explanation("Explanation")
                .evidence(json.objectNode())
                .coverage(json.objectNode())
                .recordedAt(Instant.parse("2026-10-14T12:00:00Z"));
    }
}
