package de.tum.cit.aet.hephaestus.integration.core.framework;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApprovalChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.core.spi.SubjectParser;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.TokenRefresher;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Startup-time validation that every declared {@link Capability} has its required
 * per-kind SPI beans wired. Throws so misconfigurations surface immediately, not at
 * the first request. Gated to the application-server runtime role since worker pods
 * intentionally wire only a subset of SPI beans.
 *
 * <p>Also the single place the review contract is enforced, via {@link ReviewContractValidator}; violations
 * are collected before anything throws so an operator sees every problem in one message.
 */
@Component
@ConditionalOnServerRole
public class IntegrationFrameworkBootstrap {

    private static final Logger log = LoggerFactory.getLogger(IntegrationFrameworkBootstrap.class);

    private final IntegrationManifestRegistry manifests;
    private final List<WebhookSignatureVerifier> signatureVerifiers;
    private final List<SubjectKeyDeriver> subjectKeyDerivers;
    private final List<SubjectParser> subjectParsers;
    private final List<ApiCredentialProvider> credentialProviders;
    private final List<TokenRefresher> tokenRefreshers;
    private final List<SummaryChannel> feedbackChannels;
    private final List<InlineFeedbackChannel> inlineFeedbackChannels;
    private final List<ApprovalChannel> approvalChannels;
    private final List<IntegrationLifecycleListener> lifecycleListeners;
    private final ReviewContractValidator reviewContract;
    private final boolean webhookRoleEnabled;

    public IntegrationFrameworkBootstrap(
            IntegrationManifestRegistry manifests,
            List<WebhookSignatureVerifier> signatureVerifiers,
            List<SubjectKeyDeriver> subjectKeyDerivers,
            List<SubjectParser> subjectParsers,
            List<ApiCredentialProvider> credentialProviders,
            List<TokenRefresher> tokenRefreshers,
            List<SummaryChannel> feedbackChannels,
            List<InlineFeedbackChannel> inlineFeedbackChannels,
            List<ApprovalChannel> approvalChannels,
            List<IntegrationLifecycleListener> lifecycleListeners,
            ReviewContractValidator reviewContract,
            @Value("${" + RuntimeRole.WEBHOOK_PROPERTY + ":true}") boolean webhookRoleEnabled) {
        this.manifests = manifests;
        this.signatureVerifiers = signatureVerifiers;
        this.subjectKeyDerivers = subjectKeyDerivers;
        this.subjectParsers = subjectParsers;
        this.credentialProviders = credentialProviders;
        this.tokenRefreshers = tokenRefreshers;
        this.feedbackChannels = feedbackChannels;
        this.inlineFeedbackChannels = inlineFeedbackChannels;
        this.approvalChannels = approvalChannels;
        this.lifecycleListeners = lifecycleListeners;
        this.reviewContract = reviewContract;
        this.webhookRoleEnabled = webhookRoleEnabled;
    }

    @PostConstruct
    public void validate() {
        List<String> violations = new ArrayList<>();
        // Descriptor rules first: they hold regardless of which vendors are enabled, so reporting them
        // ahead of the per-vendor ones puts the root cause on top.
        violations.addAll(reviewContract.validateDescriptors());
        for (IntegrationKind kind : manifests.registeredKinds()) {
            IntegrationManifest manifest = manifests.manifestFor(kind).orElseThrow();
            if (!manifest.enabled()) {
                // A disabled integration wires none of the required beans, so running these checks would
                // just report the feature flag back as violations; see IntegrationManifest#enabled().
                log.info("Integration {} is disabled; skipping its capability and provenance checks", kind);
                continue;
            }
            Set<Capability> declared = manifest.declaredCapabilities();
            checkRequired(kind, declared, violations);
            violations.addAll(reviewContract.validateContribution(manifest));
        }
        if (!violations.isEmpty()) {
            String joined = String.join("\n  - ", violations);
            throw new IllegalStateException("IntegrationManifest validation failed:\n  - " + joined);
        }
        log.info(
                "Integration framework bootstrap OK ({} kinds registered)",
                manifests.registeredKinds().size());
    }

    private void checkRequired(IntegrationKind kind, Set<Capability> declared, List<String> violations) {
        // Universal: every registered kind needs a credential provider.
        require(kind, "ApiCredentialProvider", anyMatchKind(credentialProviders, p -> p.kind() == kind), violations);

        // Universal: every registered kind needs a lifecycle listener so Connection state
        // transitions can be wired into the right vendor adapter (even when the body is
        // a no-op stub — that's per-kind policy, not a framework gap).
        require(
                kind,
                "IntegrationLifecycleListener",
                anyMatchKind(lifecycleListeners, l -> l.kind() == kind),
                violations);

        if (declared.contains(Capability.WEBHOOK_INGEST)) {
            // No WebhookSecretSource check here: Slack resolves its signing secret via
            // SlackSignatureVerifier, not this SPI, so requiring it would fail Slack's boot;
            // GitHub/GitLab still self-validate at construction time in their
            // WebhookSignatureVerifier (missing-bean DI failure / IllegalStateException).
            if (webhookRoleEnabled) {
                require(
                        kind,
                        "WebhookSignatureVerifier",
                        anyMatchKind(signatureVerifiers, v -> v.kind() == kind),
                        violations);
                require(kind, "SubjectKeyDeriver", anyMatchKind(subjectKeyDerivers, s -> s.kind() == kind), violations);
            }
            require(kind, "SubjectParser", anyMatchKind(subjectParsers, s -> s.kind() == kind), violations);
        }
        if (declared.contains(Capability.TOKEN_REFRESH)) {
            require(kind, "TokenRefresher", anyMatchKind(tokenRefreshers, t -> t.kind() == kind), violations);
        }
        if (declared.contains(Capability.FEEDBACK_DELIVERY)) {
            require(kind, "SummaryChannel", anyMatchKind(feedbackChannels, f -> f.kind() == kind), violations);
        }
        if (declared.contains(Capability.INLINE_FEEDBACK)) {
            require(
                    kind,
                    "InlineFeedbackChannel",
                    anyMatchKind(inlineFeedbackChannels, f -> f.kind() == kind),
                    violations);
        }
        if (declared.contains(Capability.APPROVAL_WORKFLOW)) {
            require(kind, "ApprovalChannel", anyMatchKind(approvalChannels, f -> f.kind() == kind), violations);
        }
        if (declared.contains(Capability.SCOPE_CHANGES)) {
            // SCOPE_CHANGES says "this vendor will fire onScopeChanged" — the listener
            // must therefore have a real body. We can't introspect for that, but we
            // can at least force the listener bean to be wired.
            require(
                    kind,
                    "IntegrationLifecycleListener (scope-change emitter)",
                    anyMatchKind(lifecycleListeners, l -> l.kind() == kind),
                    violations);
        }

        // Forward-compat: the moment a new Capability is added to the enum without a
        // matching check above, fail loud at boot instead of silently treating it as
        // satisfied. (Built via noneOf+addAll, not EnumSet.copyOf, because a manifest may
        // legitimately declare no capabilities — e.g. a future outbound-only integration — and
        // EnumSet.copyOf rejects an empty collection.)
        Set<Capability> unmapped = EnumSet.noneOf(Capability.class);
        unmapped.addAll(declared);
        unmapped.removeAll(ENFORCED_CAPABILITIES);
        for (Capability cap : unmapped) {
            violations.add(kind + " declares capability "
                    + cap
                    + " but the bootstrap has no enforcement rule for it — add a require() branch");
        }
    }

    /** Capabilities the {@link #checkRequired} switch above pins to a bean check. */
    private static final Set<Capability> ENFORCED_CAPABILITIES = EnumSet.of(
            Capability.WEBHOOK_INGEST,
            Capability.TOKEN_REFRESH,
            Capability.FEEDBACK_DELIVERY,
            Capability.INLINE_FEEDBACK,
            Capability.APPROVAL_WORKFLOW,
            Capability.SCOPE_CHANGES);

    private static <T> boolean anyMatchKind(List<T> beans, Predicate<T> predicate) {
        return beans.stream().anyMatch(predicate);
    }

    private static void require(IntegrationKind kind, String spi, boolean present, List<String> violations) {
        if (!present) {
            violations.add(kind + " declares capability but no " + spi + " bean is wired");
        }
    }
}
