package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class IntegrationKindRoutingTest extends BaseUnitTest {

    private final IntegrationKindRouting routing = new IntegrationKindRouting();

    @Test
    void resolvesLowercaseKnownKinds() {
        assertThat(routing.resolve("github")).contains(IntegrationKind.GITHUB);
        assertThat(routing.resolve("slack")).contains(IntegrationKind.SLACK);
    }

    @Test
    void resolvesIsCaseInsensitive() {
        assertThat(routing.resolve("GITHUB")).contains(IntegrationKind.GITHUB);
        assertThat(routing.resolve("Slack")).contains(IntegrationKind.SLACK);
    }

    @Test
    void connectionRoutedKindsHaveNoSharedEndpoint() {
        assertThat(routing.resolve("gitlab")).isEmpty();
        assertThat(routing.resolve("outline")).isEmpty();
        assertThat(routing.routes(IntegrationKind.GITLAB)).isFalse();
        assertThat(routing.routes(IntegrationKind.OUTLINE)).isFalse();
    }

    @Test
    void unknownPathSegmentReturnsEmpty() {
        // Rule: the path is attacker-controlled; never feed it to IntegrationKind.valueOf.
        assertThat(routing.resolve("bitbucket")).isEmpty();
        assertThat(routing.resolve("../../etc/passwd")).isEmpty();
        assertThat(routing.resolve("GITHUB; DROP TABLE")).isEmpty();
        assertThat(routing.resolve("")).isEmpty();
        assertThat(routing.resolve(null)).isEmpty();
    }
}
