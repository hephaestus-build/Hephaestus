package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class NatsOptionsTest extends BaseUnitTest {

    @Test
    void shouldApplyCredentialsToEveryConnectionBuilder() {
        var properties = new NatsConnectionProperties(true, "nats://localhost:4222", "user", "secret", null, null);

        var options = NatsOptions.builder(properties).build();

        assertThat(options.getUsernameChars()).containsExactly("user".toCharArray());
        assertThat(options.getPasswordChars()).containsExactly("secret".toCharArray());
    }

    @Test
    void shouldRejectPartialCredentials() {
        assertThatThrownBy(() -> new NatsConnectionProperties(true, "nats://localhost:4222", "user", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }

    @Test
    void shouldRejectPartialCredentialsWhenTheOtherHalfIsBlank() {
        // `${NATS_PASSWORD:}` binds an unset variable as "" — that must count as absent.
        assertThatThrownBy(() -> new NatsConnectionProperties(true, "nats://localhost:4222", "user", "", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured together");
    }

    @Test
    void shouldNotSendBlankCredentials() {
        var properties = new NatsConnectionProperties(true, "nats://localhost:4222", "", "", null, null);

        var options = NatsOptions.builder(properties).build();

        assertThat(options.getUsernameChars()).isNull();
        assertThat(options.getPasswordChars()).isNull();
    }
}
