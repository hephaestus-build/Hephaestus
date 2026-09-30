package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReviewOutcomeLookupAdapterTest extends BaseUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ReviewOutcomeLookupAdapter adapter = new ReviewOutcomeLookupAdapter(
            mock(AgentJobRepository.class), new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC()));

    @Test
    void shouldNameASourceThatWasNotReadByItsDisplayName() {
        JsonNode decision = mapper.readTree("""
                {"sourceChecks": [
                  {"sourceKind": "scm.pull-request.diff", "meetsRequirements": false,
                   "reasonCodes": ["SOURCE_INCOMPLETE"]},
                  {"sourceKind": "scm.pull-request.core", "meetsRequirements": true, "reasonCodes": []}
                ]}
                """);

        assertThat(adapter.blockers(decision)).containsExactly("Only part of “Code changes” was captured.");
    }

    @Test
    void shouldPhraseEverySourceProblemSoAPluralNameReadsRight() {
        JsonNode decision = mapper.readTree("""
                {"sourceChecks": [
                  {"sourceKind": "scm.pull-request.diff", "meetsRequirements": false,
                   "reasonCodes": ["SOURCE_NOT_AVAILABLE", "SOURCE_EMPTY", "SOMETHING_NEW"]}
                ]}
                """);

        assertThat(adapter.blockers(decision))
                .containsExactly(
                        "“Code changes” was not captured.",
                        "Nothing was captured from “Code changes”.",
                        "“Code changes” could not be read.");
    }

    /**
     * A practice that declares it is not reviewed automatically was stopped by policy: its sentence says so
     * and never lands among the blockers, which would report it as missing evidence.
     */
    @Test
    void shouldSayADeclaredLimitationInItsOwnSentenceAndNeverAsABlocker() {
        JsonNode humanReview = mapper.readTree("""
                {"reasonCodes": ["DECLARED_EVIDENCE_INSUFFICIENT"], "sourceChecks": []}
                """);
        JsonNode guidanceOnly = mapper.readTree("""
                {"reasonCodes": ["NO_AUTOMATED_REVIEW"], "sourceChecks": []}
                """);

        assertThat(ReviewOutcomeLookupAdapter.limitation(humanReview))
                .isEqualTo("This practice needs human review, so it is not reviewed automatically.");
        assertThat(ReviewOutcomeLookupAdapter.limitation(guidanceOnly))
                .isEqualTo("This practice is guidance only, so it is not reviewed automatically.");
        assertThat(adapter.blockers(humanReview)).isEmpty();
        assertThat(adapter.blockers(guidanceOnly)).isEmpty();
        assertThat(ReviewOutcomeLookupAdapter.limitation(mapper.readTree("{\"reasonCodes\": []}")))
                .isNull();
    }

    @Test
    void shouldFallBackToGenericWordsForASourceNoCatalogueDeclares() {
        JsonNode decision = mapper.readTree("""
                {"reasonCodes": ["DECLARED_EVIDENCE_INSUFFICIENT"],
                 "sourceChecks": [
                  {"sourceKind": "scm.pull-request.retired", "meetsRequirements": false,
                   "reasonCodes": ["SOURCE_NOT_AVAILABLE"]},
                  {"sourceKind": "Not A Kind", "meetsRequirements": false, "reasonCodes": ["SOURCE_EMPTY"]},
                  {"meetsRequirements": false, "reasonCodes": [null]}
                ]}
                """);

        assertThat(adapter.blockers(decision))
                .containsExactly(
                        "A required source was not captured.",
                        "Nothing was captured from a required source.",
                        "A required source could not be read.")
                .allSatisfy(blocker -> assertThat(blocker).doesNotContain("scm.", "Not A Kind", "_"));
    }
}
