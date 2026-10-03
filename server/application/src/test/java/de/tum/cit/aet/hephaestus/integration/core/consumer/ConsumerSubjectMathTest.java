package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-function tests for {@link ConsumerSubjectMath}. Mirrors the publisher-side coverage
 * in {@code NatsSubjectBuilderTest} and pins the consumer-side wildcard shapes that drive
 * JetStream filter creation.
 */
class ConsumerSubjectMathTest extends BaseUnitTest {

    @Nested
    @DisplayName("repositoryFilter / organizationFilter / installationAwareSubjectFilter")
    class FilterShapes {

        @Test
        void repositoryFilterForGitHubAppendsWildcardSuffix() {
            assertThat(ConsumerSubjectMath.repositoryFilter("github", "ls1intum/Artemis"))
                    .isEqualTo("github.ls1intum.Artemis.>");
        }

        @Test
        void repositoryFilterForGitLabUsesTildeJoinedNamespace() {
            assertThat(ConsumerSubjectMath.repositoryFilter("gitlab", "group/sub/project"))
                    .isEqualTo("gitlab.group~sub.project.>");
        }

        @Test
        void organizationFilterPlacesPlaceholderInRepoSlot() {
            assertThat(ConsumerSubjectMath.organizationFilter("github", "ls1intum"))
                    .isEqualTo("github.ls1intum.?.>");
        }

        @Test
        void installationAwareSubjectFilterForGithubMatchesAllInstallationEvents() {
            assertThat(ConsumerSubjectMath.installationAwareSubjectFilter(IntegrationKind.GITHUB))
                    .isEqualTo("github.?.?.>");
        }

        @Test
        void installationAwareSubjectFilterRejectsNonInstallationKinds() {
            assertThatThrownBy(() -> ConsumerSubjectMath.installationAwareSubjectFilter(IntegrationKind.GITLAB))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("GITLAB");
            assertThatThrownBy(() -> ConsumerSubjectMath.installationAwareSubjectFilter(IntegrationKind.SLACK))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void installationAwareSubjectFilterRejectsNullKind() {
            assertThatThrownBy(() -> ConsumerSubjectMath.installationAwareSubjectFilter(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class PrefixToKind {

        @Test
        void recognisesKnownKindsCaseInsensitively() {
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("github.acme.foo.issues"))
                    .contains(IntegrationKind.GITHUB);
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("GitLab.x.y.z"))
                    .contains(IntegrationKind.GITLAB);
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("SLACK.t.c.message"))
                    .contains(IntegrationKind.SLACK);
        }

        @Test
        void unknownPrefixReturnsEmpty() {
            // Explicit allow-list — never reflects on input; bitbucket is not in the map.
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("bitbucket.repo.event"))
                    .isEmpty();
        }

        @Test
        void singleTokenSubjectIsRejected() {
            // No dot means we can't isolate a prefix; this is a malformed subject, not a
            // missing-handler case. Returning empty rather than guessing is the safer
            // behaviour at the consumer's edge.
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("github")).isEmpty();
        }

        @Test
        void nullBlankAndLeadingDotAllReturnEmpty() {
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix(null)).isEmpty();
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("")).isEmpty();
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("   ")).isEmpty();
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix(".github.x")).isEmpty();
        }
    }

    @Nested
    class StreamNameFor {

        @Test
        void streamingKindsHaveStreamNames() {
            assertThat(ConsumerSubjectMath.streamNameFor(IntegrationKind.GITHUB))
                    .contains("github");
            assertThat(ConsumerSubjectMath.streamNameFor(IntegrationKind.GITLAB))
                    .contains("gitlab");
            assertThat(ConsumerSubjectMath.streamNameFor(IntegrationKind.SLACK)).contains("slack");
            assertThat(ConsumerSubjectMath.streamNameFor(IntegrationKind.OUTLINE))
                    .contains("outline");
        }

        @Test
        void nullKindReturnsEmpty() {
            // Returning Optional.empty() (rather than throwing) lets callers short-circuit without a
            // surrounding try/catch on the hot path.
            assertThat(ConsumerSubjectMath.streamNameFor(null)).isEmpty();
        }
    }

    @Nested
    class SubscriptionFilter {

        @Test
        void wrapsSubscriptionIdInAWildcardFilter() {
            assertThat(ConsumerSubjectMath.subscriptionFilter("outline", "sub-abc"))
                    .isEqualTo("outline.sub-abc.>");
        }

        @Test
        void rejectsBlankSubscriptionId() {
            assertThatThrownBy(() -> ConsumerSubjectMath.subscriptionFilter("outline", "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void outlinePrefixResolvesToKind() {
            assertThat(ConsumerSubjectMath.kindFromSubjectPrefix("outline.sub.documents~update"))
                    .contains(IntegrationKind.OUTLINE);
        }
    }

    @Nested
    class ConsumerNames {

        @Test
        void scopeConsumerNameAppendsScopeIdSuffix() {
            assertThat(ConsumerSubjectMath.scopeConsumerName("hephaestus-consumer", 42L))
                    .isEqualTo("hephaestus-consumer-scope-42");
        }

        @Test
        void installationConsumerNameAppendsInstallationSuffix() {
            assertThat(ConsumerSubjectMath.installationConsumerName("hephaestus-consumer"))
                    .isEqualTo("hephaestus-consumer-installation");
        }

        @Test
        void everyDurableNameStartsWithTheSharedPrefix() {
            // WebhookStreamMonitor tells this deployment's durables from another's by this prefix
            // alone. If a name stopped carrying it, the loss counter would silently charge nothing.
            String prefix = ConsumerSubjectMath.durablePrefix("hephaestus-consumer");

            assertThat(ConsumerSubjectMath.scopeConsumerName("hephaestus-consumer", 42L))
                    .startsWith(prefix);
            assertThat(ConsumerSubjectMath.installationConsumerName("hephaestus-consumer"))
                    .startsWith(prefix);
        }

        @Test
        void blankBaseNameIsRejected() {
            // Catching the misconfiguration here is cheaper than letting NATS reject a
            // consumer create with a partially-built name like "-scope-42".
            assertThatThrownBy(() -> ConsumerSubjectMath.scopeConsumerName(null, 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ConsumerSubjectMath.scopeConsumerName("", 1L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> ConsumerSubjectMath.installationConsumerName("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
