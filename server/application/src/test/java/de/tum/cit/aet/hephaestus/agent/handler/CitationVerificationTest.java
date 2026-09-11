package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CitationVerificationTest extends BaseUnitTest {

    @Test
    void shouldRejectUnpairedSurrogateInsteadOfHashingAReplacementByte() {
        assertThatThrownBy(() -> CitationVerification.quoteDigest(String.valueOf((char) 0xd800)))
                .isInstanceOf(JobDeliveryException.class);
    }

    @Test
    void shouldRejectMissingVerdictChangedQuoteAndDifferentAttempt() {
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        var evidence = new JsonMapper().createObjectNode();
        var citation = evidence.putArray("citations").addObject().put("quote", "exact quote");
        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
        CitationVerification.record(citation, job, "a".repeat(64), CitationVerification.quoteDigest("exact quote"));
        assertThatCode(() -> CitationVerification.requireVerified(job, evidence))
                .doesNotThrowAnyException();
        citation.put("quote", "different quote");
        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
        citation.put("quote", "exact quote");
        citation.put("path", "changed.java");
        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
        citation.remove("path");
        citation.put("revision", "a".repeat(40));
        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
        citation.remove("revision");
        job.setRetryCount(1);
        assertThatThrownBy(() -> CitationVerification.requireVerified(job, evidence))
                .isInstanceOf(JobDeliveryException.class);
    }
}
