package de.tum.cit.aet.hephaestus.integration.core.conformance;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersionScheme;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryWriter;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import io.nats.client.Message;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// A kind absent from production proves the framework is provider-neutral; persisted integration kinds still use a
// shipped value.
final class FixtureIntegration {

    /** Borrowed, not invented — see the class javadoc. */
    static final IntegrationKind KIND = IntegrationKind.OUTLINE;

    static final ItemKind WIDGET = new ItemKind("fixture.widget");

    static final ItemEventName WIDGET_ASSEMBLED = new ItemEventName("fixture.widget.assembled");
    static final ItemEventName WIDGET_SHIPPED = new ItemEventName("fixture.widget.shipped");

    static final EventTypeKey ASSEMBLY_EVENT = new EventTypeKey(KIND, "fixture.assembly");
    static final EventTypeKey SHIPMENT_EVENT = new EventTypeKey(KIND, "fixture.shipment");

    private FixtureIntegration() {}

    static PracticeReadSource need() {
        return new PracticeReadSource(new SourceKind("fixture.widget.parts"), ReadNeed.REQUIRED);
    }

    // Different event version schemes exercise the ledger's distinction between content changes and terminal events.
    static ItemDescriptor descriptor() {
        return descriptor(true, Set.of(AboutRole.AUTHOR), Set.of(CopyCapability.SUMMARY));
    }

    static ItemDescriptor descriptor(boolean reviewable, Set<AboutRole> roles, Set<CopyCapability> lanes) {
        return descriptor(
                List.of(
                        new ItemEventDefinition(
                                WIDGET_ASSEMBLED,
                                "Widget assembled",
                                Set.of(ASSEMBLY_EVENT),
                                ItemVersionScheme.CONTENT_DIGEST,
                                false,
                                true,
                                OccasionKind.CONTINUING),
                        new ItemEventDefinition(
                                WIDGET_SHIPPED,
                                "Widget shipped",
                                Set.of(SHIPMENT_EVENT),
                                ItemVersionScheme.TERMINAL_STATE,
                                false,
                                true,
                                OccasionKind.IMMEDIATE)),
                reviewable,
                roles,
                lanes);
    }

    static ItemDescriptor descriptor(
            List<ItemEventDefinition> events, boolean reviewable, Set<AboutRole> roles, Set<CopyCapability> lanes) {
        return new ItemDescriptor() {
            @Override
            public ItemKind kind() {
                return WIDGET;
            }

            @Override
            public String displayName() {
                return "Widget";
            }

            @Override
            public List<ItemEventDefinition> events() {
                return events;
            }

            @Override
            public Set<AboutRole> roles() {
                return roles;
            }

            @Override
            public Set<CopyCapability> copyCapabilities() {
                return lanes;
            }

            @Override
            public Set<ItemKind> memberKinds() {
                return Set.of();
            }

            @Override
            public String recordSchemaResource() {
                return "fixture/widget.schema.json";
            }

            @Override
            public boolean reviewable() {
                return reviewable;
            }

            @Override
            public ReviewCapabilities reviewCapabilities() {
                return new ReviewCapabilities(
                        List.of(new ReviewLimitation(
                                "FIXTURE_OBSERVES_NOTHING", "A fixture widget has no real work behind it.")),
                        List.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
            }
        };
    }

    static IntegrationManifest manifest() {
        return manifest(
                Set.of(Capability.FEEDBACK_DELIVERY),
                new IntegrationManifest.ReviewContribution(
                        Set.of(WIDGET),
                        Map.of(WIDGET, Set.of(WIDGET_ASSEMBLED, WIDGET_SHIPPED)),
                        Map.of(WIDGET, Set.of(CopyCapability.SUMMARY))));
    }

    static IntegrationManifest manifest(
            Set<Capability> capabilities, IntegrationManifest.ReviewContribution contribution) {
        return manifest(KIND, capabilities, contribution);
    }

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
            public void onMessage(Message msg) {}
        };
    }

    static ItemDescriptorRegistry itemCatalog() {
        return new ItemDescriptorRegistry(List.of(descriptor()));
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

    static SummaryWriter summaryWriter() {
        return new SummaryWriter() {
            @Override
            public IntegrationKind kind() {
                return KIND;
            }

            @Override
            public SummaryHandle postSummary(FeedbackTarget target, SummaryContent content) {
                return new SummaryHandle("fixture-summary-1", null);
            }

            @Override
            public SummaryLookup findExistingSummary(FeedbackTarget target, SummaryContent expected) {
                return SummaryLookup.absent();
            }

            @Override
            public CopyUpdateReceipt updateSummary(
                    FeedbackTarget target, SummaryHandle existing, SummaryContent content) {
                return CopyUpdateReceipt.edited(content.copyId(), existing);
            }
        };
    }
}
