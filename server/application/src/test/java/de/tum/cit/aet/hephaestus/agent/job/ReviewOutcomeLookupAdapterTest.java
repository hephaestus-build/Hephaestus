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

        assertThat(adapter.blockers(decision)).containsExactly("The review captured only part of “Code changes”.");
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
                        "The review did not capture “Code changes”.",
                        "The review captured nothing from “Code changes”.",
                        "The review could not read “Code changes”.");
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
                .isEqualTo("This practice needs human review, so no automatic review covers it.");
        assertThat(ReviewOutcomeLookupAdapter.limitation(guidanceOnly))
                .isEqualTo("This practice is guidance only, so no automatic review covers it.");
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
                        "The review did not capture a required source.",
                        "The review captured nothing from a required source.",
                        "The review could not read a required source.")
                .allSatisfy(blocker -> assertThat(blocker).doesNotContain("scm.", "Not A Kind", "_"));
    }
}
