package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandlerRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class IntegrationFrameworkBootstrapTest extends BaseUnitTest {

    @Test
    void manifestWithNoCapabilitiesValidatesCleanly() {
        // Regression: an outbound-only kind declares zero capabilities. The universal
        // credential-provider + lifecycle-listener beans are still required, but the per-capability
        // checks must be a no-op — and must not trip EnumSet.copyOf on the empty declared set.
        IntegrationManifestRegistry registry =
                new IntegrationManifestRegistry(List.of(manifest(IntegrationKind.SLACK, Set.of())));
        IntegrationFrameworkBootstrap bootstrap = bootstrap(
                registry,
                List.of(credentialProvider(IntegrationKind.SLACK)),
                List.of(lifecycleListener(IntegrationKind.SLACK)));

        assertThatCode(bootstrap::validate).doesNotThrowAnyException();
    }

    @Test
    void declaredCapabilityWithoutItsBeanFailsLoud() {
        IntegrationManifestRegistry registry = new IntegrationManifestRegistry(
                List.of(manifest(IntegrationKind.GITHUB, Set.of(Capability.WEBHOOK_INGEST))));
        // Universal beans present, but no WebhookSignatureVerifier backing the WEBHOOK_INGEST claim.
        IntegrationFrameworkBootstrap bootstrap = bootstrap(
                registry,
                List.of(credentialProvider(IntegrationKind.GITHUB)),
                List.of(lifecycleListener(IntegrationKind.GITHUB)));

        assertThatThrownBy(bootstrap::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WebhookSignatureVerifier");
    }

    @Test
    void webhookIngestDoesNotRequireASeparateSecretSourceBean() {
        IntegrationManifestRegistry registry = new IntegrationManifestRegistry(
                List.of(manifest(IntegrationKind.SLACK, Set.of(Capability.WEBHOOK_INGEST))));
        IntegrationFrameworkBootstrap bootstrap = bootstrap(
                registry,
                List.of(credentialProvider(IntegrationKind.SLACK)),
                List.of(lifecycleListener(IntegrationKind.SLACK)),
                List.of(webhookSignatureVerifier(IntegrationKind.SLACK)),
                List.of(subjectKeyDeriver(IntegrationKind.SLACK)),
                List.of(subjectParser(IntegrationKind.SLACK)));

        assertThatCode(bootstrap::validate).doesNotThrowAnyException();
    }

    @Test
    void webhookIngestDoesNotRequireWebhookBeansWhenWebhookRoleIsDisabled() {
        IntegrationManifestRegistry registry = new IntegrationManifestRegistry(
                List.of(manifest(IntegrationKind.SLACK, Set.of(Capability.WEBHOOK_INGEST))));
        IntegrationFrameworkBootstrap bootstrap = bootstrap(
                registry,
                List.of(credentialProvider(IntegrationKind.SLACK)),
                List.of(lifecycleListener(IntegrationKind.SLACK)),
                List.of(),
                List.of(),
                List.of(subjectParser(IntegrationKind.SLACK)),
                false);

        assertThatCode(bootstrap::validate).doesNotThrowAnyException();
    }

    @Test
    void webhookIngestStillRequiresSubjectParserWhenWebhookRoleIsDisabled() {
        IntegrationManifestRegistry registry = new IntegrationManifestRegistry(
                List.of(manifest(IntegrationKind.SLACK, Set.of(Capability.WEBHOOK_INGEST))));
        IntegrationFrameworkBootstrap bootstrap = bootstrap(
                registry,
                List.of(credentialProvider(IntegrationKind.SLACK)),
                List.of(lifecycleListener(IntegrationKind.SLACK)),
                List.of(),
                List.of(),
                List.of(),
                false);

        assertThatThrownBy(bootstrap::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SubjectParser");
    }

    private static IntegrationFrameworkBootstrap bootstrap(
            IntegrationManifestRegistry registry,
            List<ApiCredentialProvider> credentialProviders,
            List<IntegrationLifecycleListener> lifecycleListeners) {
        return bootstrap(registry, credentialProviders, lifecycleListeners, List.of(), List.of(), List.of());
    }

    private static IntegrationFrameworkBootstrap bootstrap(
            IntegrationManifestRegistry registry,
            List<ApiCredentialProvider> credentialProviders,
            List<IntegrationLifecycleListener> lifecycleListeners,
            List<WebhookSignatureVerifier> signatureVerifiers,
            List<SubjectKeyDeriver> subjectKeyDerivers,
            List<SubjectParser> subjectParsers) {
        return bootstrap(
                registry,
                credentialProviders,
                lifecycleListeners,
                signatureVerifiers,
                subjectKeyDerivers,
                subjectParsers,
                true);
    }

    private static IntegrationFrameworkBootstrap bootstrap(
            IntegrationManifestRegistry registry,
            List<ApiCredentialProvider> credentialProviders,
            List<IntegrationLifecycleListener> lifecycleListeners,
            List<WebhookSignatureVerifier> signatureVerifiers,
            List<SubjectKeyDeriver> subjectKeyDerivers,
            List<SubjectParser> subjectParsers,
            boolean webhookRoleEnabled) {
        return new IntegrationFrameworkBootstrap(
                registry,
                signatureVerifiers,
                subjectKeyDerivers,
                subjectParsers,
                credentialProviders,
                List.of(), // tokenRefreshers
                List.of(), // feedbackChannels
                List.of(), // inlineFeedbackChannels
                List.of(), // approvalChannels
                lifecycleListeners,
                // A validator over an empty world: these tests are about capability wiring, and every manifest
                // below contributes nothing to review, so the review contract has nothing to say about them.
                new ReviewContractValidator(
                        new ArtifactDescriptorRegistry(List.of()),
                        new IntegrationMessageHandlerRegistry(List.of()),
                        List.of(),
                        Set::of),
                webhookRoleEnabled);
    }

    private static IntegrationManifest manifest(IntegrationKind kind, Set<Capability> capabilities) {
        return new IntegrationManifest() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public String displayName() {
                return kind.name();
            }

            @Override
            public Set<Capability> declaredCapabilities() {
                return capabilities;
            }

            @Override
            public ReviewContribution reviewContribution() {
                return ReviewContribution.none();
            }
        };
    }

    private static ApiCredentialProvider credentialProvider(IntegrationKind kind) {
        return new ApiCredentialProvider() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public Optional<CredentialBundle> resolve(IntegrationRef ref) {
                return Optional.empty();
            }
        };
    }

    private static IntegrationLifecycleListener lifecycleListener(IntegrationKind kind) {
        return () -> kind;
    }

    private static WebhookSignatureVerifier webhookSignatureVerifier(IntegrationKind kind) {
        return new WebhookSignatureVerifier() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public VerificationResult verify(WebhookRequest request) {
                return new VerificationResult.Verified();
            }
        };
    }

    private static SubjectKeyDeriver subjectKeyDeriver(IntegrationKind kind) {
        return new SubjectKeyDeriver() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public String deriveSubject(JsonNode payload, Map<String, String> headers) {
                return kind.name().toLowerCase(Locale.ROOT) + ".test";
            }

            @Override
            public String deriveDedupKey(byte[] body, Map<String, String> headers) {
                return "test";
            }
        };
    }

    private static SubjectParser subjectParser(IntegrationKind kind) {
        return new SubjectParser() {
            @Override
            public IntegrationKind kind() {
                return kind;
            }

            @Override
            public EventTypeKey parse(String fullSubject) {
                return new EventTypeKey(kind, "test");
            }
        };
    }
}
