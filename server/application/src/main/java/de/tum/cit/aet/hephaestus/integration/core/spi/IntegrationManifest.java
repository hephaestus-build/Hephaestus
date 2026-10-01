package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Per-kind capability declaration validated at application-server startup: every declared {@link Capability}
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
     * Must never be gated with {@code @ConditionalOnProperty}: a manifest is registered whether or not its
     * vendor is enabled, so removing the bean would make that vendor's signals read as signals nothing in
     * the build can raise. The flag instead governs only the wiring — credential provider, subject parser,
     * message handlers.
     */
    default boolean enabled() {
        return true;
    }

    /**
     * What this integration contributes to practice review. Has no default on purpose: an integration that
     * contributes nothing must say {@link ReviewContribution#none()} explicitly, since inheriting silence
     * is how an integration ends up ingesting events that never trigger anything.
     */
    ReviewContribution reviewContribution();

    /**
     * The roles this integration may name only in a later capture from the provider, after the occasion that
     * concerns them is raised. Merge admission consults {@link ActorRole#MERGER} for merged work, holding its review
     * until the merger is named; no other role is consulted.
     */
    default Set<ActorRole> rolesNamedAfterTheOccasion() {
        return Set.of();
    }

    /** Whether a stored native review commit identifies the current work head; unknown without that authority. */
    default String reviewCommitFor(@Nullable String recordedCommit, @Nullable String currentHead) {
        return "UNKNOWN";
    }

    /**
     * The practice-review section of a manifest. {@link #raises()} is deliberately a subset of what the
     * artifact's {@link ArtifactDescriptor} declares — the descriptor states what the domain can express,
     * this states what one vendor delivers of it — so the gap reads as a dormant binding rather than a
     * practice that quietly never fires for that vendor's workspaces.
     *
     * @param raises   every entry must be declared by that kind's descriptor and backed by an ingested
     *                 event of this integration
     * @param delivers each lane is held to the {@link Capability} that carries it
     */
    record ReviewContribution(
            Set<ArtifactKind> observes,
            Map<ArtifactKind, Set<SignalName>> raises,
            Map<ArtifactKind, Set<FeedbackLane>> delivers) {
        private static final ReviewContribution NONE = new ReviewContribution(Set.of(), Map.of(), Map.of());

        public ReviewContribution {
            observes = Set.copyOf(Objects.requireNonNullElse(observes, Set.of()));
            raises = deepCopy(Objects.requireNonNullElse(raises, Map.of()));
            delivers = deepCopy(Objects.requireNonNullElse(delivers, Map.of()));
        }

        /** An integration that takes no part in practice review — a content source, or not wired yet. */
        public static ReviewContribution none() {
            return NONE;
        }

        /** Every signal this integration raises, across all kinds. */
        public Set<SignalName> allRaisedSignals() {
            return raises.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        }

        private static <V> Map<ArtifactKind, Set<V>> deepCopy(Map<ArtifactKind, Set<V>> source) {
            return source.entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        }
    }
}
