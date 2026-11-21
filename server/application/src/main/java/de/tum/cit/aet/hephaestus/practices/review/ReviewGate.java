package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.UncapturableReads;
import de.tum.cit.aet.hephaestus.practices.UnofferedItemCapabilities;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewGate {
    private final PracticeRepository practices;
    private final PracticeItemOptions items;
    private final JsonMapper json;
    private final ClasspathSourceCatalogRegistry sources;

    /**
     * The practices that run on this review. An event review runs those that the joined occasions select, and those
     * that an earlier occasion still owes ({@code carried}): they wait for the item's checks, or their review was replaced.
     */
    @Transactional(readOnly = true)
    public GateDecision evaluate(
            ReviewWorkspace workspace,
            ItemSnapshot item,
            ReviewOrigin origin,
            Set<ItemEventName> occasions,
            Set<Long> carried,
            Collection<Long> selected) {
        if (workspace.id() != item.item().workspaceId())
            throw new InvalidInputException("The item belongs to another workspace.");
        var refusal = refusal(workspace, origin);
        if (refusal.isPresent()) return refusal.orElseThrow();
        var descriptor = items.require(item.item().kind());
        var matched = practices
                .findByWorkspaceIdAndKind(workspace.id(), item.item().kind())
                .stream()
                .filter(p -> selected.isEmpty() || selected.contains(p.getId()))
                .filter(p -> runnable(p, descriptor, item.state(), origin, occasions, carried.contains(p.getId())))
                .toList();
        if (matched.isEmpty())
            return new GateDecision.Skip("No practice can review this item.", ReviewRequestReason.NO_ACTIVE_PRACTICE);
        return new GateDecision.Run(
                workspace, matched, workspace.reviewSettings().rolloutRevision(), origin);
    }

    /**
     * The one practice that a trial tries, in the version it tries. It is loaded by its id, so a draft practice runs
     * here and nowhere else (ADR 0118 decision 7).
     */
    @Transactional(readOnly = true)
    public GateDecision trial(
            ReviewWorkspace workspace, ItemSnapshot item, Practice practice, PracticeVersion version) {
        if (workspace.id() != item.item().workspaceId()
                || !Objects.equals(practice.getWorkspace().getId(), workspace.id()))
            throw new InvalidInputException("The item belongs to another workspace.");
        var refusal = refusal(workspace, ReviewOrigin.TRIAL);
        if (refusal.isPresent()) return refusal.orElseThrow();
        var definition = json.convertValue(version.getDefinition(), PracticeDefinition.class);
        if (!practice.getKind().equals(item.item().kind())
                || selectable(definition, items.require(item.item().kind())).isEmpty())
            return new GateDecision.Skip(
                    "Hephaestus cannot review this practice on this work.", ReviewRequestReason.NO_ACTIVE_PRACTICE);
        return new GateDecision.Run(
                workspace, List.of(practice), workspace.reviewSettings().rolloutRevision(), ReviewOrigin.TRIAL);
    }

    Optional<GateDecision.Skip> refusal(ReviewWorkspace workspace, ReviewOrigin origin) {
        if (!workspace.active())
            return Optional.of(
                    new GateDecision.Skip("The workspace is inactive.", ReviewRequestReason.WORKSPACE_INACTIVE));
        if (!Boolean.TRUE.equals(workspace.features().practicesEnabled()))
            return Optional.of(
                    new GateDecision.Skip("Practice reviews are disabled.", ReviewRequestReason.PRACTICES_DISABLED));
        boolean enabled = Boolean.TRUE.equals(
                origin == ReviewOrigin.EVENT
                        ? workspace.features().eventReviewsEnabled()
                        : workspace.features().requestReviewsEnabled());
        if (!enabled)
            return Optional.of(new GateDecision.Skip(
                    origin == ReviewOrigin.EVENT
                            ? "Reviews from work activity are disabled."
                            : "Requested reviews are disabled.",
                    ReviewRequestReason.GATE_WITHHELD));
        return Optional.empty();
    }

    /**
     * Whether the practice runs on this occasion. A stored version is not revalidated, so a practice whose events or
     * About role the item kind does not offer, or whose reads the current source catalog cannot capture for it, is
     * never selected: capture would withhold it on every review and leave each review partial.
     */
    private boolean runnable(
            Practice practice,
            ItemDescriptor descriptor,
            Map<String, String> state,
            ReviewOrigin origin,
            Set<ItemEventName> occasions,
            boolean carried) {
        var definition = selectable(practice, descriptor).orElse(null);
        if (definition == null) return false;
        if (origin != ReviewOrigin.EVENT) return true;
        return (carried || occasions.stream().anyMatch(definition.reviewOn().events()::contains))
                && definition.reviewOn().state().entrySet().stream()
                        .allMatch(e ->
                                state.containsKey(e.getKey()) && e.getValue().contains(state.get(e.getKey())));
    }

    /**
     * The definition of a practice that Hephaestus reviews, whose events and About role its item kind offers, and whose
     * reads the current source catalog can capture for that kind.
     */
    Optional<PracticeDefinition> selectable(Practice practice, ItemDescriptor descriptor) {
        return selectable(
                json.convertValue(practice.getCurrentVersion().getDefinition(), PracticeDefinition.class), descriptor);
    }

    private Optional<PracticeDefinition> selectable(PracticeDefinition definition, ItemDescriptor descriptor) {
        if (definition.reviewedBy().by() != ReviewedByKind.HEPHAESTUS) return Optional.empty();
        if (UnofferedItemCapabilities.of(descriptor, definition) != null) return Optional.empty();
        if (UncapturableReads.of(
                        sources.current(), definition.reads(), descriptor.kind().value())
                != null) return Optional.empty();
        return Optional.of(definition);
    }
}
