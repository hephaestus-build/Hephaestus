package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ProviderResourceIdentityTest {
    @Test
    void tokenFingerprintHasNoCredentialEncryptionKeyDependency() {
        assertThat(ProviderResourceAdapter.slackTokenFingerprint("test-token"))
                .isEqualTo(ProviderResourceAdapter.fingerprint("hephaestus/slack-bot-token/v1test-token"))
                .hasSize(64)
                .isNotEqualTo(ProviderResourceAdapter.fingerprint("test-token"));
    }

    @Test
    void anUpstreamIdCannotCollideAcrossOriginsParentsOrResourceKinds() {
        var base = resource("https://provider.example.test", "group-one", "same-id", ResourceType.GROUP_HOOK);
        assertThat(base.identity())
                .isNotEqualTo(resource("https://other.example.test", "group-one", "same-id", ResourceType.GROUP_HOOK)
                        .identity())
                .isNotEqualTo(resource(base.origin(), "group-two", "same-id", ResourceType.GROUP_HOOK)
                        .identity())
                .isNotEqualTo(resource(base.origin(), "group-one", "same-id", ResourceType.REGISTRATION)
                        .identity());
    }

    @Test
    void aProviderReplyCannotTurnAnUnknownCreationIntoACompletedRemoval() {
        var registration = new ProviderResourceRepair(
                resource("https://provider.example.test", "group", "marker", ResourceType.REGISTRATION), Instant.EPOCH);
        assertThatThrownBy(() -> registration.transition(ProviderResourceRepair.State.PENDING, Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registration.getState()).isEqualTo(ProviderResourceRepair.State.REGISTERING);
    }

    @Test
    void aRemovalMustBeDispatchedBeforeItCanAwaitConfirmation() {
        var removal = new ProviderResourceRepair(
                resource("https://provider.example.test", "group", "hook", ResourceType.GROUP_HOOK), Instant.EPOCH);
        assertThatThrownBy(() -> removal.transition(ProviderResourceRepair.State.AWAITING_CONFIRMATION, Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class);
        removal.transition(ProviderResourceRepair.State.CHECKING, Instant.EPOCH);
        removal.transition(ProviderResourceRepair.State.DISPATCHING, Instant.EPOCH);
        removal.transition(ProviderResourceRepair.State.AWAITING_CONFIRMATION, Instant.EPOCH.plusSeconds(1));
        assertThat(removal.getState()).isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
    }

    @Test
    void shouldBackOffUncertainAndUnauthorizedAttemptsToAnHourlyCap() {
        for (var state : List.of(
                ProviderResourceRepair.State.AWAITING_CONFIRMATION, ProviderResourceRepair.State.NEEDS_AUTHORIZATION)) {
            var row = new ProviderResourceRepair(
                    resource("https://provider.example.test", "group", "hook", ResourceType.GROUP_HOOK), Instant.EPOCH);
            Instant now = Instant.EPOCH;
            for (long delay : new long[] {60, 120, 240, 480, 960, 1920, 3600, 3600}) {
                row.transition(ProviderResourceRepair.State.CHECKING, now);
                row.transition(state, now);
                assertThat(row.getNextAttemptAt()).isEqualTo(now.plusSeconds(delay));
                now = row.getNextAttemptAt();
            }
        }
    }

    private static Resource resource(String origin, String parent, String id, ResourceType type) {
        return new Resource(IntegrationKind.GITLAB, origin, type, parent, id, null);
    }
}
