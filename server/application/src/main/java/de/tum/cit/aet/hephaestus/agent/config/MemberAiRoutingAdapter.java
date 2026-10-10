package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private final PersonProcessingSuppression suppression;

    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> binding(long workspaceId, AgentPurpose purpose, @Nullable Long developerId) {
        return route(workspaceId, purpose, developerId);
    }

    /**
     * {@link #precomputeBinding} calls this instead of {@link #binding}: a call to another transactional method of
     * this bean skips its proxy.
     */
    private Optional<WorkspaceAgentBinding> route(long workspaceId, AgentPurpose purpose, @Nullable Long developerId) {
        if (developerId != null && suppression.isUserSuppressed(developerId)) return Optional.empty();
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return Optional.empty();
        var choice = decision.choice();
        if (choice == null) {
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspaceId, purpose, DataHandlingTier.UNDECLARED)
                    .filter(this::servesUndeclared);
        }
        return choice.ceiling()
                .flatMap(ceiling -> loosestWithin(bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose), ceiling));
    }

    /**
     * The binding that serves one precompute purpose of a review about the developer. A precompute script sends
     * the reviewed work to its models, so its ceiling is the stricter of the developer's ceiling and the tier of
     * the review binding that serves the developer: work that the review keeps in-house never goes to a cloud
     * precompute model. Empty when no review binding serves the developer.
     */
    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> precomputeBinding(
            long workspaceId, AgentPurpose purpose, @Nullable Long developerId) {
        var review = route(workspaceId, AgentPurpose.PRACTICE_REVIEW, developerId);
        if (review.isEmpty()) return Optional.empty();
        var reviewTier = review.get().getDataHandlingTier();
        if (reviewTier == DataHandlingTier.UNDECLARED) return route(workspaceId, purpose, developerId);
        return loosestWithin(bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose), reviewTier);
    }

    /**
     * The tiers whose members find no ready binding of {@code purpose}: {@code IN_HOUSE} and {@code CLOUD} for
     * the members who chose them, and {@code UNDECLARED} for the members who have not chosen, when the workspace
     * lets them leave the choice open. The tiers are judged as {@link #servedTiers} judges them.
     */
    @Transactional(readOnly = true)
    public List<DataHandlingTier> unservedTiers(long workspaceId, AgentPurpose purpose) {
        var rows = bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose);
        var reviewRows = AgentPurpose.precompute().contains(purpose)
                ? bindings.findByWorkspaceIdAndPurpose(workspaceId, AgentPurpose.PRACTICE_REVIEW)
                : List.<WorkspaceAgentBinding>of();
        boolean choiceOptional = choiceOptional(workspaceId);
        var serving = serving(rows, reviewRows, choiceOptional);
        return memberTiers(choiceOptional).stream()
                .filter(tier -> !serving.containsKey(tier))
                .toList();
    }

    /**
     * For each purpose and binding tier of the workspace, the members that the binding serves now, named by the
     * tier of their choice as in {@link #unservedTiers}. A precompute purpose is capped at the tier of the review
     * binding that serves the same members, as {@link #precomputeBinding} caps it. Members whom no review binding
     * serves are judged at their own ceiling: no review runs for them, and the review purpose reports that gap.
     */
    @Transactional(readOnly = true)
    public Map<AgentPurpose, Map<DataHandlingTier, List<DataHandlingTier>>> servedTiers(long workspaceId) {
        var rows = bindings.findByWorkspaceIdWithModels(workspaceId).stream()
                .collect(Collectors.groupingBy(
                        WorkspaceAgentBinding::getPurpose,
                        () -> new EnumMap<>(AgentPurpose.class),
                        Collectors.toList()));
        boolean choiceOptional = choiceOptional(workspaceId);
        var served = new EnumMap<AgentPurpose, Map<DataHandlingTier, List<DataHandlingTier>>>(AgentPurpose.class);
        rows.forEach((purpose, own) -> {
            var reviewRows = AgentPurpose.precompute().contains(purpose)
                    ? rows.getOrDefault(AgentPurpose.PRACTICE_REVIEW, List.of())
                    : List.<WorkspaceAgentBinding>of();
            var bySlot = new EnumMap<DataHandlingTier, List<DataHandlingTier>>(DataHandlingTier.class);
            serving(own, reviewRows, choiceOptional)
                    .forEach((member, binding) -> bySlot.computeIfAbsent(
                                    binding.getDataHandlingTier(), slot -> new ArrayList<>())
                            .add(member));
            served.put(purpose, bySlot);
        });
        return served;
    }

    /** The member tiers that can exist in the workspace, in the order {@link #unservedTiers} names them. */
    private static List<DataHandlingTier> memberTiers(boolean choiceOptional) {
        return choiceOptional
                ? List.of(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD, DataHandlingTier.UNDECLARED)
                : List.of(DataHandlingTier.IN_HOUSE, DataHandlingTier.CLOUD);
    }

    private boolean choiceOptional(long workspaceId) {
        return !preferences.forDeveloper(workspaceId, null).choiceRequired();
    }

    /**
     * The binding that serves each member tier, from the purpose's rows. {@code reviewRows} caps a chosen tier at
     * the tier of the review binding that serves it; it is empty for a purpose that the review does not cap.
     */
    private Map<DataHandlingTier, WorkspaceAgentBinding> serving(
            List<WorkspaceAgentBinding> rows, List<WorkspaceAgentBinding> reviewRows, boolean choiceOptional) {
        var serving = new EnumMap<DataHandlingTier, WorkspaceAgentBinding>(DataHandlingTier.class);
        for (var choice : List.of(MemberAiChoice.IN_HOUSE_ONLY, MemberAiChoice.CLOUD)) {
            var ceiling = choice.ceiling().orElseThrow();
            var effective = loosestWithin(reviewRows, ceiling)
                    .map(WorkspaceAgentBinding::getDataHandlingTier)
                    .orElse(ceiling);
            loosestWithin(rows, effective).ifPresent(binding -> serving.put(ceiling, binding));
        }
        if (choiceOptional) {
            rows.stream()
                    .filter(binding -> binding.getDataHandlingTier() == DataHandlingTier.UNDECLARED)
                    .filter(this::servesUndeclared)
                    .findFirst()
                    .ifPresent(binding -> serving.put(DataHandlingTier.UNDECLARED, binding));
        }
        return serving;
    }

    @Transactional(readOnly = true)
    public boolean allows(long workspaceId, @Nullable Long developerId, LlmModelResolver.ConnectionRef model) {
        if (developerId != null && suppression.isUserSuppressed(developerId)) return false;
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return false;
        if (model.workspaceId() == null || model.workspaceId() != workspaceId) return false;
        var tier = models.dataHandlingTier(model);
        var choice = decision.choice();
        if (choice == null)
            return tier.filter(DataHandlingTier.UNDECLARED::equals).isPresent();
        return choice.ceiling()
                .map(ceiling ->
                        tier.filter(current -> current.isWithin(ceiling)).isPresent())
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Option> options(long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        // Reviews that are off have no rows worth loading; each purpose is loaded once and shared by both
        // choices.
        var reviewRows = rowsIfEnabled(
                workspaceId,
                AgentPurpose.PRACTICE_REVIEW,
                workspace.getFeatures().getPracticesEnabled());
        var mentorRows = bindings.findByWorkspaceIdAndPurpose(workspaceId, AgentPurpose.MENTOR);
        var choices = List.of(MemberAiChoice.IN_HOUSE_ONLY, MemberAiChoice.CLOUD);
        var options = new ArrayList<Option>();
        for (var choice : choices) {
            var review = choice.ceiling().flatMap(ceiling -> loosestWithin(reviewRows, ceiling));
            var mentor = choice.ceiling().flatMap(ceiling -> loosestWithin(mentorRows, ceiling));
            var models = Stream.concat(review.stream(), mentor.stream())
                    .map(MemberAiRoutingAdapter::model)
                    .flatMap(Optional::stream)
                    .distinct()
                    .toList();
            options.add(new Option(choice, review.isPresent(), mentor.isPresent(), models));
        }
        return List.copyOf(options);
    }

    /** The ready binding's model declarations; the connection URL stays on the server. */
    private static Optional<WorkspaceAiAvailability.Model> model(WorkspaceAgentBinding binding) {
        var instance = binding.getInstanceModel();
        if (instance != null) {
            return Optional.of(new WorkspaceAiAvailability.Model(
                    instance.getDisplayName(),
                    instance.getBrand(),
                    instance.getConnection().getConnectionPlatform(),
                    instance.getDataHandlingTier()));
        }
        var own = binding.getWorkspaceModel();
        if (own != null) {
            return Optional.of(new WorkspaceAiAvailability.Model(
                    own.getDisplayName(),
                    own.getBrand(),
                    own.getConnection().getConnectionPlatform(),
                    own.getDataHandlingTier()));
        }
        return Optional.empty();
    }

    private List<WorkspaceAgentBinding> rowsIfEnabled(
            long workspaceId, AgentPurpose purpose, @Nullable Boolean featureEnabled) {
        return Boolean.TRUE.equals(featureEnabled)
                ? bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose)
                : List.of();
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

    /** A binding of the {@code UNDECLARED} slot serves while it is ready and its model is still undeclared. */
    private boolean servesUndeclared(WorkspaceAgentBinding binding) {
        return ready(binding) && currentTier(binding) == DataHandlingTier.UNDECLARED;
    }

    private static DataHandlingTier currentTier(WorkspaceAgentBinding binding) {
        var instance = binding.getInstanceModel();
        if (instance != null) return instance.getDataHandlingTier();
        var own = binding.getWorkspaceModel();
        return own != null ? own.getDataHandlingTier() : DataHandlingTier.UNDECLARED;
    }
}
