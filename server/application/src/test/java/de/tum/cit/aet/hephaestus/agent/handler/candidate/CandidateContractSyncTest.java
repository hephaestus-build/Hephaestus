package de.tum.cit.aet.hephaestus.agent.handler.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.practices.spi.OmissionReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

// Wire refusal and omission names must agree across the host and admission boundary.
class CandidateContractSyncTest extends BaseUnitTest {

    private static final Pattern REFUSALS = Pattern.compile("export const CANDIDATE_REFUSALS = \\[([^\\]]*)]");
    private static final Pattern QUOTED = Pattern.compile("\"([a-z_]+)\"");
    private static final Pattern QUOTED_ANY = Pattern.compile("\"([^\"]+)\"");

    @Test
    void shouldRefuseInOneOrderWhenTheHostAndAdmissionNameARefusal() throws IOException {
        Matcher refusals = REFUSALS.matcher(contract());
        assertThat(refusals.find())
                .as("contract.ts declares CANDIDATE_REFUSALS")
                .isTrue();
        List<String> runner = QUOTED.matcher(refusals.group(1))
                .results()
                .map(code -> code.group(1))
                .toList();

        assertThat(runner)
                .containsExactlyElementsOf(Arrays.stream(CandidateRefusal.values())
                        .filter(value -> value != CandidateRefusal.BASIS_STATE_UNSUPPORTED
                                && value != CandidateRefusal.BASIS_REVISION_UNSUPPORTED
                                && value != CandidateRefusal.QUOTE_UNVERIFIED
                                && value != CandidateRefusal.SOURCE_UNAVAILABLE
                                && value != CandidateRefusal.BASIS_WITNESS_MISSING
                                && value != CandidateRefusal.DUTY_QUOTE_UNVERIFIED
                                && value != CandidateRefusal.CONSULTED_SOURCE_UNREAD
                                && value != CandidateRefusal.UNDECLARED_SOURCE_CITED
                                && value != CandidateRefusal.UNFINISHED_RUN_CITED)
                        .map(CandidateRefusal::wireValue)
                        .toList());
    }

    @Test
    void shouldNameOmissionReasonsAlikeWhenTheHostAndAdmissionReadCoverage() throws IOException {
        String contract = contract();
        Matcher reasons =
                Pattern.compile("export const REASONS = \\[([^\\]]*)]").matcher(contract);
        assertThat(reasons.find()).as("contract.ts declares REASONS").isTrue();
        Matcher omissions =
                Pattern.compile("export const OMISSION_REASONS = \\[([^\\]]*)]").matcher(contract);
        assertThat(omissions.find()).as("contract.ts declares OMISSION_REASONS").isTrue();
        List<String> runner = new ArrayList<>();
        Pattern.compile("\"([^\"]+)\"|\\.\\.\\.REASONS")
                .matcher(omissions.group(1))
                .results()
                .forEach(item ->
                        runner.addAll(item.group(1) == null ? quoted(reasons.group(1)) : List.of(item.group(1))));

        assertThat(runner)
                .containsExactlyElementsOf(Arrays.stream(OmissionReason.values())
                        .map(OmissionReason::wireValue)
                        .toList());
    }

    private static List<String> quoted(String text) {
        return QUOTED_ANY.matcher(text).results().map(code -> code.group(1)).toList();
    }

    private static String contract() throws IOException {
        return Files.readString(resolveRepoFile("docker/agents/practice-scripts/lib/core/contract.ts"));
    }

    private static Path resolveRepoFile(String relativePath) {
        Path candidate = Path.of("..", "..").resolve(relativePath);
        return Files.exists(candidate) ? candidate : Path.of(relativePath);
    }
}
