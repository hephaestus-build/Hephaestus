package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackNotSentException;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class PostedTextTest extends BaseUnitTest {

    /** A body and the text Hephaestus posts for it; {@code ProviderMentionsLiveTest} renders each posted text. */
    record Case(String body, String posted) {}

    static List<Case> cases() throws IOException {
        try (InputStream json =
                Objects.requireNonNull(PostedTextTest.class.getResourceAsStream("/scm/mentions/posted-text.json"))) {
            return List.of(JsonMapper.builder().build().readValue(json, Case[].class));
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void shouldPostOnlyPlainMarkdown(Case text) {
        assertThat(PostedText.of(text.body())).isEqualTo(text.posted());
        assertThat(PostedText.of(text.posted())).isEqualTo(text.posted());
    }

    @Test
    void shouldKeepTheHiddenMarkerAfterTheSafeText() {
        var content = new SummaryContent(
                UUID.randomUUID(),
                "Ask @alice <!-- quoted -->\n\n<!-- hephaestus-feedback-copy:1 -->",
                "key",
                "<!-- hephaestus-feedback-copy:1 -->");
        assertThat(PostedText.summary(content))
                .isEqualTo("Ask <code>@alice</code>\n\n<!-- hephaestus-feedback-copy:1 -->");
    }

    @Test
    void shouldNotSendTextThatIsEmptyOnceItIsSafe() {
        assertThatThrownBy(() -> PostedText.of("<!-- only hidden -->")).isInstanceOf(FeedbackNotSentException.class);
    }
}
