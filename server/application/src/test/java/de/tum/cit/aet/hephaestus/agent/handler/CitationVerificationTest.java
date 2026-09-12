package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CitationVerificationTest extends BaseUnitTest {

    @Test
    void shouldRejectUnpairedSurrogateInsteadOfHashingAReplacementByte() {
        assertThatThrownBy(() -> CitationVerification.quoteDigest(String.valueOf((char) 0xd800)))
                .isInstanceOf(JobDeliveryException.class);
    }

    @Test
    void shouldRejectCitationWhenNoVerdictWasRecorded() {
        var job = job();
        var evidence = new JsonMapper().createObjectNode();
        evidence.putArray("citations").addObject().put("quote", "exact quote");

        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
    }

    @Test
    void shouldAcceptCitationWhenVerdictMatchesQuoteIdentityAndAttempt() {
        var job = job();
        var evidence = new JsonMapper().createObjectNode();
        var citation = evidence.putArray("citations").addObject().put("quote", "exact quote");
        CitationVerification.record(citation, job, "a".repeat(64), CitationVerification.quoteDigest("exact quote"));

        assertThatCode(() -> CitationVerification.requireVerified(job, evidence))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutationsAfterVerdict")
    void shouldRejectCitationWhenMutatedAfterVerdict(
            String ignoredDescription, BiConsumer<ObjectNode, AgentJob> mutation) {
        var job = job();
        var evidence = new JsonMapper().createObjectNode();
        var citation = evidence.putArray("citations").addObject().put("quote", "exact quote");
        CitationVerification.record(citation, job, "a".repeat(64), CitationVerification.quoteDigest("exact quote"));

        mutation.accept(citation, job);

        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
    }

    static Stream<Arguments> mutationsAfterVerdict() {
        return Stream.of(
                mutation("changed quote", (citation, job) -> citation.put("quote", "different quote")),
                mutation("changed path", (citation, job) -> citation.put("path", "changed.java")),
                mutation("changed revision", (citation, job) -> citation.put("revision", "a".repeat(40))),
                mutation("different attempt", (citation, job) -> job.setRetryCount(1)));
    }

    private static Arguments mutation(String description, BiConsumer<ObjectNode, AgentJob> mutation) {
        return Arguments.of(description, mutation);
    }

    private static AgentJob job() {
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        return job;
    }
}
