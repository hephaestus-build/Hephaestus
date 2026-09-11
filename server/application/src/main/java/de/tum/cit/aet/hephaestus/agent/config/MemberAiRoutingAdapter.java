package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one home of the routing rule: a developer's {@link MemberAiChoice} is the loosest
 * {@link DataHandlingTier} they accept, so any ready binding at or under that ceiling may serve them
 * and the loosest of those is picked. Nothing ever routes a chosen member to a looser tier or to the
 * {@code UNDECLARED} slot, which serves only members who have not chosen where the choice is optional.
 */
@Service
@RequiredArgsConstructor
public class MemberAiRoutingAdapter implements WorkspaceAiAvailability {
    private final WorkspaceAgentBindingRepository bindings;
    private final MemberAiPreferences preferences;
    private final LlmModelResolver models;
    private final WorkspaceRepository workspaces;

    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> binding(long workspaceId, AgentPurpose purpose, @Nullable Long developerId) {
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return Optional.empty();
        var choice = decision.choice();
        if (choice == null) {
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspaceId, purpose, DataHandlingTier.UNDECLARED)
                    .filter(this::ready);
        }
        return choice.ceiling()
                .flatMap(ceiling -> loosestWithin(bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose), ceiling));
    }

    @Transactional(readOnly = true)
    public boolean allows(long workspaceId, @Nullable Long developerId, LlmModelResolver.ConnectionRef model) {
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return false;
        var choice = decision.choice();
        if (choice == null) return true;
        return choice.ceiling()
                .map(ceiling -> models.dataHandlingTier(model).isWithin(ceiling))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Option> options(long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        // A purpose whose feature is off has no rows worth loading; each enabled purpose is loaded once
        // and shared by the three choices.
        var reviewRows = rowsIfEnabled(
                workspaceId,
                AgentPurpose.PRACTICE_REVIEW,
                workspace.getFeatures().getPracticesEnabled());
        var mentorRows = rowsIfEnabled(
                workspaceId, AgentPurpose.MENTOR, workspace.getFeatures().getMentorEnabled());
        return Stream.of(MemberAiChoice.IN_HOUSE_ONLY, MemberAiChoice.NOT_KEPT_ONLY, MemberAiChoice.ANY_DECLARED)
                .map(choice -> new Option(choice, ready(reviewRows, choice), ready(mentorRows, choice)))
                .toList();
    }

    private List<WorkspaceAgentBinding> rowsIfEnabled(
            long workspaceId, AgentPurpose purpose, @Nullable Boolean featureEnabled) {
        return Boolean.TRUE.equals(featureEnabled)
                ? bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose)
                : List.of();
    }

    private boolean ready(List<WorkspaceAgentBinding> rows, MemberAiChoice choice) {
        return choice.ceiling().flatMap(ceiling -> loosestWithin(rows, ceiling)).isPresent();
    }

    private Optional<WorkspaceAgentBinding> loosestWithin(List<WorkspaceAgentBinding> rows, DataHandlingTier ceiling) {
        return rows.stream()
                .filter(binding -> binding.getDataHandlingTier().isWithin(ceiling))
                .filter(this::ready)
                .max(Comparator.comparing(WorkspaceAgentBinding::getDataHandlingTier));
    }

    private boolean ready(WorkspaceAgentBinding binding) {
        return binding.isEnabled() && models.isAvailable(binding);
    }
}
