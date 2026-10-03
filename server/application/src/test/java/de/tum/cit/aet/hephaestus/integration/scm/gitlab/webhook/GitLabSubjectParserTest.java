package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class GitLabSubjectParserTest extends BaseUnitTest {

    private final GitLabSubjectParser parser = new GitLabSubjectParser();

    @Test
    void parsesSimpleMergeRequestSubject() {
        EventTypeKey key = parser.parse("gitlab.acme.web.merge_request");

        assertThat(key.kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(key.eventType()).isEqualTo("merge_request");
    }

    @Test
    void parsesTildeEscapedNestedNamespace() {
        // The deriver joins nested groups with ~ (since / is the NATS hierarchy
        // separator). Parser must remain agnostic about namespace shape.
        EventTypeKey key = parser.parse("gitlab.acme~devops.frontend.push");

        assertThat(key.kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(key.eventType()).isEqualTo("push");
    }

    @Test
    void parsesAllStandardGitlabEvents() {
        // Smoke-test that the common GitLab object_kind values round-trip the parser
        // without surprise normalization.
        for (String event : new String[] {
            "merge_request", "push", "issue", "note", "pipeline", "build", "wiki_page", "tag_push", "release",
        }) {
            EventTypeKey key = parser.parse("gitlab.acme.web." + event);
            assertThat(key.eventType()).isEqualTo(event);
        }
    }

    @Test
    void tooFewComponentsThrows() {
        assertThatThrownBy(() -> parser.parse("gitlab.acme.web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 4 components");

        assertThatThrownBy(() -> parser.parse("gitlab.acme")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> parser.parse("gitlab")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongPrefixThrows() {
        assertThatThrownBy(() -> parser.parse("github.acme.web.merge_request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gitlab");
    }

    @Test
    void blankOrNullSubjectThrows() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyEventComponentThrows() {
        assertThatThrownBy(() -> parser.parse("gitlab.acme.web."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void parserIdentifiesAsGitlabKind() {
        assertThat(parser.kind()).isEqualTo(IntegrationKind.GITLAB);
    }
}
