package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Per-kind capability declaration validated at server startup: every declared {@link Capability}
 * must have a matching SPI bean for the same {@link IntegrationKind}, or {@code IntegrationFrameworkBootstrap}
 * fails fast.
 *
 * <p>Capabilities describe plumbing — whether webhooks arrive, whether tokens refresh — and say nothing about
 * what the integration contributes to a practice review; that is the separate {@link #reviewContribution()}.
 */
public interface IntegrationManifest {
    IntegrationKind kind();

    String displayName();

    Set<Capability> declaredCapabilities();

    /**
     * A disabled vendor still declares its item kinds and events. Gate credential providers,
     * subject parsers and handlers instead of the manifest.
     */
    default boolean enabled() {
        return true;
    }

    /** Every integration declares its review contribution, including {@link ReviewContribution#none()}. */
    ReviewContribution reviewContribution();

    /** Whether a stored native review commit identifies the current work head; unknown without that authority. */
    default String reviewCommitFor(@Nullable String recordedCommit, @Nullable String currentHead) {
        return "UNKNOWN";
    }

    /**
     * The practice-review section of a manifest. {@link #raises()} is deliberately a subset of what
     * the item's {@link ItemDescriptor} declares — the descriptor states what the domain can
     * express, this states what one vendor delivers of it — so the gap reads as a dormant binding
     * rather than a practice that quietly never fires for that vendor's workspaces.
     *
     * @param raises every entry must be declared by that kind's descriptor and backed by an
     *     ingested event of this integration
     * @param delivers each lane is held to the {@link Capability} that carries it
     */
    record ReviewContribution(
            Set<ItemKind> observes,
            Map<ItemKind, Set<ItemEventName>> raises,
            Map<ItemKind, Set<CopyCapability>> delivers) {
        private static final ReviewContribution NONE = new ReviewContribution(Set.of(), Map.of(), Map.of());

        public ReviewContribution {
            observes = Set.copyOf(Objects.requireNonNullElse(observes, Set.of()));
            raises = deepCopy(Objects.requireNonNullElse(raises, Map.of()));
            delivers = deepCopy(Objects.requireNonNullElse(delivers, Map.of()));
        }

        /** An integration with no review contribution. */
        public static ReviewContribution none() {
            return NONE;
        }

        public Set<ItemEventName> allRaisedEvents() {
            return raises.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        }

        private static <V> Map<ItemKind, Set<V>> deepCopy(Map<ItemKind, Set<V>> source) {
            return source.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }
}
