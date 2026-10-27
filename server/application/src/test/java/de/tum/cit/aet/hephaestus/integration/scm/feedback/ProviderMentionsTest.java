package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class ProviderMentionsTest extends BaseUnitTest {

    /**
     * A body and the text Hephaestus posts for it. {@code ProviderMentionsLiveTest} renders each escaped body with the
     * GitHub and GitLab Markdown APIs and finds no mention in it.
     */
    record Case(String body, String escaped) {}

    static List<Case> cases() throws IOException {
        try (InputStream json = Objects.requireNonNull(
                ProviderMentionsTest.class.getResourceAsStream("/scm/mentions/provider-mentions.json"))) {
            return List.of(JsonMapper.builder().build().readValue(json, Case[].class));
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldPostEachBodyWithItsMentionsAsCode(Case mention) {
        assertThat(ProviderMentions.escape(mention.body())).isEqualTo(mention.escaped());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldLeaveAnEscapedBodyAsItIs(Case mention) {
        assertThat(ProviderMentions.escape(mention.escaped())).isEqualTo(mention.escaped());
    }
}
