package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// A chosen AI tier is a ceiling; UNDECLARED serves only members without a choice where choice is optional.
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

    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> bindingForPeople(
            long workspaceId, AgentPurpose purpose, List<@Nullable Long> people) {
        return routePeople(workspaceId, purpose, people);
    }

    private Optional<WorkspaceAgentBinding> routePeople(
            long workspaceId, AgentPurpose purpose, List<@Nullable Long> people) {
        if (people.isEmpty())
            throw new IllegalArgumentException("A review must declare its subjects, including nobody");
        var candidates = bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose).stream()
                .filter(models::isAvailable)
                .filter(row -> people.stream()
                        .allMatch(person -> permitsModel(workspaceId, person, models.connectionRef(row))));
        var ordered = candidates
                .sorted(Comparator.comparingInt(row -> switch (row.getDataHandlingTier()) {
                    case IN_HOUSE -> 0;
                    case CLOUD -> 1;
                    case UNDECLARED -> 2;
                }))
                .toList();
        return ordered.isEmpty()
                ? Optional.empty()
                : Optional.of(people.stream().allMatch(Objects::isNull) ? ordered.getFirst() : ordered.getLast());
    }

    // Self-invocation bypasses Spring's transactional proxy; route within the caller's transaction.
    private Optional<WorkspaceAgentBinding> route(long workspaceId, AgentPurpose purpose, @Nullable Long developerId) {
        if (developerId == null) {
            if (!preferences.forDeveloper(workspaceId, null).permitsAi()) return Optional.empty();
            return routePeople(workspaceId, purpose, Collections.singletonList(null));
        }
        if (suppression.isAccountSuppressed(developerId)) return Optional.empty();
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return Optional.empty();
        var choice = decision.choice();
        if (choice == null) {
            return bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                            workspaceId, purpose, DataHandlingTier.UNDECLARED)
                    .filter(this::servesUndeclared);
        }
        return ceiling(choice)
                .flatMap(ceiling -> loosestWithin(bindings.findByWorkspaceIdAndPurpose(workspaceId, purpose), ceiling));
    }

    /**
     * For each purpose and binding tier of the workspace, the members that the binding serves now, named by the
     * tier of their choice: {@code IN_HOUSE} and {@code CLOUD} for the members who chose them, and {@code
     * UNDECLARED} for the members who have not chosen, when the workspace lets them leave the choice open.
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
            var bySlot = new EnumMap<DataHandlingTier, List<DataHandlingTier>>(DataHandlingTier.class);
            serving(own, choiceOptional)
                    .forEach((member, binding) -> bySlot.computeIfAbsent(
                                    binding.getDataHandlingTier(), slot -> new ArrayList<>())
                            .add(member));
            served.put(purpose, bySlot);
        });
        return served;
    }

    private boolean choiceOptional(long workspaceId) {
        return !preferences.forDeveloper(workspaceId, null).choiceRequired();
    }

    private Map<DataHandlingTier, WorkspaceAgentBinding> serving(
            List<WorkspaceAgentBinding> rows, boolean choiceOptional) {
        var serving = new EnumMap<DataHandlingTier, WorkspaceAgentBinding>(DataHandlingTier.class);
        for (var choice : List.of(AiChoice.IN_HOUSE_ONLY, AiChoice.CLOUD)) {
            var ceiling = ceiling(choice).orElseThrow();
            loosestWithin(rows, ceiling).ifPresent(binding -> serving.put(ceiling, binding));
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
        return permitsModel(workspaceId, developerId, model);
    }

    private boolean permitsModel(long workspaceId, @Nullable Long developerId, LlmModelResolver.ConnectionRef model) {
        if (developerId != null && suppression.isAccountSuppressed(developerId)) return false;
        if (model.workspaceId() == null || model.workspaceId() != workspaceId) return false;
        if (developerId == null) return models.dataHandlingTier(model).isPresent();
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return false;
        var tier = models.dataHandlingTier(model);
        var choice = decision.choice();
        if (choice == null)
            return tier.filter(DataHandlingTier.UNDECLARED::equals).isPresent();
        return ceiling(choice)
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
        var choices = List.of(AiChoice.IN_HOUSE_ONLY, AiChoice.CLOUD);
        var options = new ArrayList<Option>();
        for (var choice : choices) {
            var review = ceiling(choice).flatMap(ceiling -> loosestWithin(reviewRows, ceiling));
            var mentor = ceiling(choice).flatMap(ceiling -> loosestWithin(mentorRows, ceiling));
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

    private boolean servesUndeclared(WorkspaceAgentBinding binding) {
        return ready(binding) && currentTier(binding) == DataHandlingTier.UNDECLARED;
    }

    private static DataHandlingTier currentTier(WorkspaceAgentBinding binding) {
        var instance = binding.getInstanceModel();
        if (instance != null) return instance.getDataHandlingTier();
        var own = binding.getWorkspaceModel();
        return own != null ? own.getDataHandlingTier() : DataHandlingTier.UNDECLARED;
    }

    private static Optional<DataHandlingTier> ceiling(AiChoice choice) {
        return switch (choice) {
            case NO_AI -> Optional.empty();
            case IN_HOUSE_ONLY -> Optional.of(DataHandlingTier.IN_HOUSE);
            case CLOUD -> Optional.of(DataHandlingTier.CLOUD);
        };
    }
}
