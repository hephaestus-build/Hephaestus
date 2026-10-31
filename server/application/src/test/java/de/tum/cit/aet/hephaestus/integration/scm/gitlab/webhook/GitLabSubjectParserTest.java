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
    void parsesAConnectionSubject() {
        EventTypeKey key = parser.parse("gitlab.?connection.7.merge_request");

        assertThat(key.kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(key.eventType()).isEqualTo("merge_request");
    }

    @Test
    void parsesAllStandardGitlabEvents() {
        // Smoke-test that the common GitLab object_kind values round-trip the parser
        // without surprise normalization.
        for (String event : new String[] {
            "merge_request", "push", "issue", "note", "pipeline", "build", "wiki_page", "tag_push", "release",
        }) {
            EventTypeKey key = parser.parse("gitlab.?connection.7." + event);
            assertThat(key.eventType()).isEqualTo(event);
        }
    }

    @Test
    void rejectsAPathSubject() {
        // Only a connection's own route publishes GitLab deliveries; a namespace/project subject has no publisher.
        assertThatThrownBy(() -> parser.parse("gitlab.acme.web.merge_request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gitlab.?connection.");
        assertThatThrownBy(() -> parser.parse("gitlab.acme~devops.frontend.push"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnyOtherComponentCount() {
        assertThatThrownBy(() -> parser.parse("gitlab.?connection.7"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 4 components");
        assertThatThrownBy(() -> parser.parse("gitlab.?connection.7.merge.request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 4 components");
        assertThatThrownBy(() -> parser.parse("gitlab")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongPrefixThrows() {
        assertThatThrownBy(() -> parser.parse("github.?connection.7.merge_request"))
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
        assertThatThrownBy(() -> parser.parse("gitlab.?connection.7."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void parserIdentifiesAsGitlabKind() {
        assertThat(parser.kind()).isEqualTo(IntegrationKind.GITLAB);
    }
}
