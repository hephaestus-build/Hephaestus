package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.EvidenceSnapshotFixtures;
import de.tum.cit.aet.hephaestus.evidence.AppliesWhenCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.CapturedApplicability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.AppliesWhen;
import de.tum.cit.aet.hephaestus.practices.AppliesWhenClause;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AppliesWhenEvaluatorTest extends BaseUnitTest {
    private final AppliesWhen conditions =
            new AppliesWhen(List.of(new AppliesWhenClause(List.of("**/*.java"), null, null)), "No Java files.");
    private final JobFolderIndex manifest = new JobFolderIndex(
            new SourceContractVersion("3.0.0"),
            "0".repeat(64),
            "scm.pull_request",
            Instant.EPOCH,
            List.of(new SourceCapture(
                    new SourceKind("scm.pull-request.diff"),
                    new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER),
                    EvidenceSnapshotFixtures.citation(new SourceKind("scm.pull-request.diff")),
                    List.of())),
            List.of(),
            List.of(),
            List.of());

    @Test
    void shouldTolerateAnAbsentAdapter() {
        var evaluator = new AppliesWhenEvaluator(List.of());
        assertThat(evaluator.sources(new ItemKind(manifest.itemKind()), conditions))
                .isEmpty();
        assertThat(evaluator.evaluate(conditions, manifest, Map.of())).isNull();
    }

    @Test
    void shouldIgnorePracticesWithoutConditions() {
        assertThat(new AppliesWhenEvaluator(List.of()).evaluate(null, manifest, Map.of()))
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(AppliesWhenCheck.Result.class)
    void shouldRecordNeutralAdapterDecisions(AppliesWhenCheck.Result result) {
        var source = new SourceKind("scm.pull-request.diff");
        CapturedApplicability adapter = (kind, clauses, captures, files) ->
                List.of(new AppliesWhenCheck.Clause("CHANGED_PATH", source, result));
        var evaluator = new AppliesWhenEvaluator(List.of(adapter));
        assertThat(evaluator.sources(new ItemKind(manifest.itemKind()), conditions))
                .containsExactly(source);
        var check = evaluator.evaluate(conditions, manifest, Map.of());
        assertThat(check).isNotNull();
        assertThat(check.absent()).isEqualTo(result == AppliesWhenCheck.Result.NOT_FOUND);
        assertThat(check.otherwise()).isEqualTo(conditions.otherwise());
        assertThat(check.clauses()).containsExactly(new AppliesWhenCheck.Clause("CHANGED_PATH", source, result));
    }
}
