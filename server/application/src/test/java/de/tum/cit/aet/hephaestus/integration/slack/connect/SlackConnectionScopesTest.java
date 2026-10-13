package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Guards the OAuth scope set for the shipped Slack subsystem. */
@Tag("unit")
class SlackConnectionScopesTest extends BaseUnitTest {

    @Test
    void requestsExactlyTheLiveSubsystemScopes() {
        assertThat(SlackConnectionStrategy.DEFAULT_SCOPES)
                .containsExactlyInAnyOrder(
                        "chat:write",
                        "assistant:write",
                        "im:history",
                        "channels:history",
                        "groups:history",
                        "channels:read",
                        "channels:join",
                        "groups:read",
                        "reactions:write",
                        "users:read");
    }

    @Test
    void omitsBroadScopesThatCurrentCodeDoesNotNeed() {
        assertThat(SlackConnectionStrategy.DEFAULT_SCOPES)
                .doesNotContain(
                        "chat:write.public",
                        "team:read",
                        "commands",
                        "app_mentions:read",
                        "mpim:read",
                        "mpim:history",
                        "im:write",
                        "users:read.email");
    }
}
