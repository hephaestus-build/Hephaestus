package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.UnofferedItemCapabilities;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
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

    @Transactional(readOnly = true)
    public GateDecision evaluate(
            Workspace workspace,
            ItemSnapshot item,
            ReviewOrigin origin,
            @Nullable ItemEventName event,
            Collection<Long> selected) {
        if (!Objects.equals(workspace.getId(), item.item().workspaceId()))
            throw new IllegalArgumentException("The item belongs to another workspace.");
        var refusal = refusal(workspace, origin);
        if (refusal.isPresent()) return refusal.orElseThrow();
        var descriptor = items.require(item.item().kind());
        var matched = practices
                .findByWorkspaceIdAndKind(workspace.getId(), item.item().kind())
                .stream()
                .filter(p -> selected.isEmpty() || selected.contains(p.getId()))
                .filter(p -> runnable(p, descriptor, item.state(), origin, event))
                .toList();
        if (matched.isEmpty())
            return new GateDecision.Skip("No practice can review this item.", ReviewRequestReason.NO_ACTIVE_PRACTICE);
        return new GateDecision.Run(
                workspace, matched, workspace.getReviewSettings().getRolloutRevision(), origin);
    }

    Optional<GateDecision.Skip> refusal(Workspace workspace, ReviewOrigin origin) {
        if (workspace.getStatus() != Workspace.WorkspaceStatus.ACTIVE)
            return Optional.of(
                    new GateDecision.Skip("The workspace is inactive.", ReviewRequestReason.WORKSPACE_INACTIVE));
        if (!Boolean.TRUE.equals(workspace.getFeatures().getPracticesEnabled()))
            return Optional.of(
                    new GateDecision.Skip("Practice reviews are disabled.", ReviewRequestReason.PRACTICES_DISABLED));
        boolean enabled = Boolean.TRUE.equals(
                origin == ReviewOrigin.EVENT
                        ? workspace.getFeatures().getPracticeReviewAutoTriggerEnabled()
                        : workspace.getFeatures().getPracticeReviewManualTriggerEnabled());
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
     * About role the item kind does not offer is never selected: capture would withhold it on every review and leave
     * each review partial. Its practice page names the definition error instead.
     */
    private boolean runnable(
            Practice practice,
            ItemDescriptor descriptor,
            Map<String, String> state,
            ReviewOrigin origin,
            @Nullable ItemEventName event) {
        var definition = selectable(practice, descriptor).orElse(null);
        if (definition == null) return false;
        if (origin != ReviewOrigin.EVENT) return true;
        return event != null
                && definition.reviewOn().events().contains(event)
                && definition.reviewOn().state().entrySet().stream()
                        .allMatch(e ->
                                state.containsKey(e.getKey()) && e.getValue().contains(state.get(e.getKey())));
    }

    /** The definition of a practice that Hephaestus reviews and whose events and About role its item kind offers. */
    Optional<PracticeDefinition> selectable(Practice practice, ItemDescriptor descriptor) {
        var definition = json.convertValue(practice.getCurrentVersion().getDefinition(), PracticeDefinition.class);
        if (definition.reviewedBy().by() != ReviewedByKind.HEPHAESTUS) return Optional.empty();
        if (UnofferedItemCapabilities.of(descriptor, definition) != null) return Optional.empty();
        return Optional.of(definition);
    }
}
