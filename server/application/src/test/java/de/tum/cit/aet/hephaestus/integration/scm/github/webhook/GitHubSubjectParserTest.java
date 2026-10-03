package de.tum.cit.aet.hephaestus.integration.scm.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class GitHubSubjectParserTest extends BaseUnitTest {

    private final GitHubSubjectParser parser = new GitHubSubjectParser();

    @Test
    void parsesRepositorySubject() {
        EventTypeKey key = parser.parse("github.acme.web.pull_request");

        assertThat(key.kind()).isEqualTo(IntegrationKind.GITHUB);
        assertThat(key.eventType()).isEqualTo("repository.pull_request");
    }

    @Test
    void parsesOrganizationTier() {
        EventTypeKey key = parser.parse("github.acme.?.member");

        assertThat(key.eventType()).isEqualTo("organization.member");
    }

    @Test
    void parsesInstallationTier() {
        EventTypeKey key = parser.parse("github.?.?.installation");

        assertThat(key.eventType()).isEqualTo("installation.installation");
    }

    @Test
    void rejoinsTailComponentsWhenEventHasDots() {
        // The deriver sanitizes '.' inside tokens to '~', but a defensive parser must still
        // accept any tail it actually receives without losing components.
        EventTypeKey key = parser.parse("github.acme.web.installation.repositories");

        assertThat(key.eventType()).isEqualTo("repository.installation.repositories");
    }

    @Test
    void tooFewComponentsThrows() {
        assertThatThrownBy(() -> parser.parse("github.acme.web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 4");
    }

    @Test
    void wrongPrefixThrows() {
        assertThatThrownBy(() -> parser.parse("gitlab.acme.web.push"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("github.");
    }

    @Test
    void blankOrNullSubjectThrows() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parserIdentifiesAsGithubKind() {
        assertThat(parser.kind()).isEqualTo(IntegrationKind.GITHUB);
    }
}
