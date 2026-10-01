package de.tum.cit.aet.hephaestus.integration.core.conformance;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactCatalog;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewExecutionCatalog;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import io.nats.client.Message;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An integration that reviews an artifact Hephaestus has never heard of — the standing proof that the
 * review contract is real, not a description of what GitHub happens to do. {@code fixture.widget} exists
 * nowhere in {@code src/main}, so if the framework or the practices module ever grows a dependency on a
 * concrete artifact kind, the tests built on this fixture stop compiling or stop passing.
 *
 * <p>Two deliberate compromises:
 * <ul>
 *   <li>It borrows an existing {@link IntegrationKind} rather than adding a fixture-only constant, since
 *       that enum is persisted on connections and jobs.
 *   <li>It cannot be driven through {@code ReviewGate} (which takes a
 *       {@code PullRequest}/{@code Issue}), so the practices-side proof runs through the kind-agnostic
 *       {@code PracticeSignalCoverage} instead.
 * </ul>
 */
final class FixtureIntegration {

    /** Borrowed, not invented — see the class javadoc. */
    static final IntegrationKind KIND = IntegrationKind.OUTLINE;

    static final ArtifactKind WIDGET = ArtifactKind.of("fixture.widget");

    static final SignalName WIDGET_ASSEMBLED = SignalName.of("fixture.widget.assembled");
    static final SignalName WIDGET_SHIPPED = SignalName.of("fixture.widget.shipped");

    static final EventTypeKey ASSEMBLY_EVENT = new EventTypeKey(KIND, "fixture.assembly");
    static final EventTypeKey SHIPMENT_EVENT = new EventTypeKey(KIND, "fixture.shipment");

    private FixtureIntegration() {}

    /** The evidence a binding on a widget reads — a source kind no contract in {@code src/main} declares. */
    static PracticeEvidenceRequirement need() {
        return new PracticeEvidenceRequirement(new SourceKind("fixture.widget.parts"), EvidenceStance.REQUIRED);
    }

    /**
     * Two signals with different revision schemes on purpose: one keyed on what a person wrote, one
     * terminal. A fixture where both behaved alike would not exercise the per-signal rule the ledger
     * turns on.
     */
    static ArtifactDescriptor descriptor() {
        return descriptor(true, Set.of(ActorRole.AUTHOR), Set.of(FeedbackLane.IN_CONTEXT_SUMMARY));
    }

    static ArtifactDescriptor descriptor(boolean reviewable, Set<ActorRole> roles, Set<FeedbackLane> lanes) {
        return descriptor(
                List.of(
                        new Signal(
                                WIDGET_ASSEMBLED,
                                "Widget assembled",
                                Set.of(ASSEMBLY_EVENT),
                                RevisionScheme.CONTENT_DIGEST),
                        new Signal(
                                WIDGET_SHIPPED,
                                "Widget shipped",
                                Set.of(SHIPMENT_EVENT),
                                RevisionScheme.TERMINAL_STATE)),
                reviewable,
                roles,
                lanes);
    }

    static ArtifactDescriptor descriptor(
            List<Signal> signals, boolean reviewable, Set<ActorRole> roles, Set<FeedbackLane> lanes) {
        return new ArtifactDescriptor() {
            @Override
            public ArtifactKind kind() {
                return WIDGET;
            }

            @Override
            public String displayName() {
                return "Widget";
            }

            @Override
            public List<Signal> signals() {
                return signals;
            }

            @Override
            public Set<ActorRole> roles() {
                return roles;
            }

            @Override
            public Set<FeedbackLane> lanes() {
                return lanes;
            }

            @Override
            public boolean reviewable() {
                return reviewable;
            }

            @Override
            public List<ReviewLimitation> reviewLimitations() {
                // A reviewable kind must name what its evidence cannot settle.
                return List.of(new ReviewLimitation(
                        "FIXTURE_OBSERVES_NOTHING", "A fixture widget has no real work behind it."));
            }
        };
    }

    static IntegrationManifest manifest() {
        return manifest(
                Set.of(Capability.FEEDBACK_DELIVERY),
                new IntegrationManifest.ReviewContribution(
                        Set.of(WIDGET),
                        Map.of(WIDGET, Set.of(WIDGET_ASSEMBLED, WIDGET_SHIPPED)),
                        Map.of(WIDGET, Set.of(FeedbackLane.IN_CONTEXT_SUMMARY))));
    }

    static IntegrationManifest manifest(
            Set<Capability> capabilities, IntegrationManifest.ReviewContribution contribution) {
        return manifest(KIND, capabilities, contribution);
    }

    /**
     * The same fixture integration under a second kind, for the case where two integrations raise
     * different signals about one artifact and only one of them is connected.
     */
    static IntegrationManifest manifest(
            IntegrationKind integrationKind,
            Set<Capability> capabilities,
            IntegrationManifest.ReviewContribution contribution) {
        return new IntegrationManifest() {
            @Override
            public IntegrationKind kind() {
                return integrationKind;
            }

            @Override
            public String displayName() {
                return "Fixture widget factory";
            }

            @Override
            public Set<Capability> declaredCapabilities() {
                return capabilities;
            }

            @Override
            public ReviewContribution reviewContribution() {
                return contribution;
            }
        };
    }

    static IntegrationMessageHandler handler(EventTypeKey key) {
        return new IntegrationMessageHandler() {
            @Override
            public EventTypeKey key() {
                return key;
            }

            @Override
            public void onMessage(Message msg) {
                // The registry only ever asks whether a key resolves; nothing here dispatches a message.
            }
        };
    }

    static ReviewContextBuilder contextBuilder() {
        return () -> WIDGET;
    }

    /**
     * The fixture's stand-in for a job type and a registered handler. A widget has neither and cannot —
     * {@code AgentJobType} is a compiled enum in the agent module — so the executability the contract
     * insists on is supplied here directly.
     */
    static ReviewExecutionCatalog executionCatalog() {
        return () -> Set.of(WIDGET);
    }

    /** A build that can run nothing — for asserting the executability rule bites. */
    static ReviewExecutionCatalog noExecutionCatalog() {
        return Set::of;
    }

    /** The catalog a practices-module surface sees when this fixture is the only registered domain. */
    static ArtifactCatalog artifactCatalog() {
        ArtifactDescriptor descriptor = descriptor();
        return new ArtifactCatalog() {
            @Override
            public java.util.Collection<ArtifactDescriptor> all() {
                return List.of(descriptor);
            }

            @Override
            public Optional<ArtifactDescriptor> descriptorFor(ArtifactKind kind) {
                return descriptor.kind().equals(kind) ? Optional.of(descriptor) : Optional.empty();
            }
        };
    }

    static ApiCredentialProvider credentialProvider() {
        return new ApiCredentialProvider() {
            @Override
            public IntegrationKind kind() {
                return KIND;
            }

            @Override
            public Optional<CredentialBundle> resolve(IntegrationRef ref) {
                return Optional.empty();
            }
        };
    }

    static IntegrationLifecycleListener lifecycleListener() {
        return () -> KIND;
    }

    /** The bean {@code FEEDBACK_DELIVERY} promises; removing it from a bootstrap must fail the boot. */
    static SummaryChannel feedbackChannel() {
        return new SummaryChannel() {
            @Override
            public IntegrationKind kind() {
                return KIND;
            }

            @Override
            public SummaryHandle postSummary(FeedbackTarget target, FeedbackContent content) {
                return new SummaryHandle("fixture-summary-1");
            }

            @Override
            public String formatPullRequestSubjectId(String repoFullName, int prNumber) {
                return repoFullName + "~" + prNumber;
            }
        };
    }
}
