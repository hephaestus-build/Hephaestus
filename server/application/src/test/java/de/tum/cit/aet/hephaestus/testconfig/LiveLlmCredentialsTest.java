package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("unit")
class LiveLlmCredentialsTest {

    @ParameterizedTest
    @CsvSource({
        "https://models.example, https://models.example/v1/chat/completions",
        "https://models.example/v1, https://models.example/v1/chat/completions",
        "https://models.example/v1/, https://models.example/v1/chat/completions"
    })
    void shouldSendEveryChatRequestToTheOneChatEndpointOfTheBase(String base, String endpoint) {
        assertThat(new LiveLlmCredentials(base, "key", "model").chatCompletions())
                .hasToString(endpoint);
    }
}
