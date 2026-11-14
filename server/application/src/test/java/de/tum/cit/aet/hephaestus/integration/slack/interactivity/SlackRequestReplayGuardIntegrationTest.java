package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SlackRequestReplayGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SlackRequestReplayGuard guard;

    @Test
    void shouldClaimEachSignatureOnce() {
        String signature = "v0=" + UUID.randomUUID().toString().replace("-", "");

        assertThat(guard.claim(signature)).isTrue();
        assertThat(guard.claim(signature)).isFalse();
        assertThat(guard.claim(signature + "0")).isTrue();
    }
}
