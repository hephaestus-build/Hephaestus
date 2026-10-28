package de.tum.cit.aet.hephaestus.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class AutomatedReviewReadinessReportTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @EnumSource(AppliesWhenCheck.Result.class)
    void shouldSerializeTheSchemaProtocolFixture(AppliesWhenCheck.Result result) throws Exception {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var source = new SourceKind("scm.pull-request.diff");
        var version = new SourceContractVersion("3.0.0");
        var check = new AppliesWhenCheck(
                result == AppliesWhenCheck.Result.NOT_FOUND,
                "No Java files changed.",
                List.of(new AppliesWhenCheck.Clause("CHANGED_PATH", source, result)));
        var decision = new AutomatedReviewReadinessDecision(
                "java-files",
                at,
                true,
                List.of(),
                List.of(new SourceReadinessCheck(source, version, at, at, true, List.of())));
        var report = new AutomatedReviewReadinessReport(
                version,
                new ClasspathSourceCatalogRegistry(mapper, Clock.systemUTC()).catalogDigest(),
                "scm.pull_request",
                at,
                at,
                List.of(decision),
                Map.of("java-files", check));
        try (var fixture =
                Objects.requireNonNull(getClass().getResourceAsStream("/evidence/readiness/expected.json"))) {
            assertThat(mapper.readTree(mapper.writeValueAsBytes(report)))
                    .isEqualTo(mapper.readTree(fixture).path(result.name()));
        }
        assertThat(report.runs(decision)).isEqualTo(result != AppliesWhenCheck.Result.NOT_FOUND);
    }
}
