package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.dto.ClearablePracticeField;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);

    private final PracticeRepository practiceRepository;
    private final PracticeGroupRepository practiceGroupRepository;
    private final PracticeGroupService practiceGroupService;
    private final ConfigAuditPort configAudit;
    private final PracticeRevisionService practiceRevisionService;
    private final WorkspaceRepository workspaceRepository;
    private final PracticeDefinitionValidator definitionValidator;
    private final PracticeEvidenceDefaults evidenceDefaults;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;

    /**
     * The workspace catalogue, optionally narrowed to one autonomy.
     *
     * <p>The filter is on the <em>effective</em> autonomy, which is the only autonomy an administrator can see on
     * the screen they are filtering. Filtering the stored column instead would answer "which practices
     * happen to hold this value", and would return nothing at all for the autonomy most practices are actually
     * at — the inherited one.
     */
    @Transactional(readOnly = true)
    public List<Practice> listPractices(WorkspaceContext ctx, @Nullable PracticeAutonomy autonomy) {
        log.debug("Listing practices for workspace {} (autonomy={})", ctx.slug(), autonomy);
        List<Practice> all = practiceRepository.findAllForCatalog(ctx.id());
        if (autonomy == null) {
            return all;
        }
        PracticeAutonomy workspaceDefault =
                workspaceDefaults.forWorkspace(ctx.id()).defaultAutonomy();
        return all.stream()
                .filter(p -> AutonomyResolver.effectiveAutonomyOf(p, workspaceDefault) == autonomy)
                .toList();
    }

    /**
     * Every practice this workspace actually reviews, at any effective autonomy above {@code OFF}.
     *
     * <p>Includes {@code HUMAN_APPROVAL}: that autonomy promises the developer no <em>feedback</em>, not concealment,
     * so the developer-facing catalogue lists what is observed while the autonomy governs what is said.
     */
    @Transactional(readOnly = true)
    public List<Practice> listReviewedPractices(WorkspaceContext ctx) {
        PracticeAutonomy workspaceDefault =
                workspaceDefaults.forWorkspace(ctx.id()).defaultAutonomy();
        return practiceRepository.findAllForCatalog(ctx.id()).stream()
                .filter(p -> AutonomyResolver.effectiveAutonomyOf(p, workspaceDefault)
                        .admitsReview())
                .toList();
    }

    @Transactional
    public void reorderPractices(WorkspaceContext ctx, String groupSlug, List<String> orderedSlugs) {
        if (new HashSet<>(orderedSlugs).size() != orderedSlugs.size()) {
            throw new IllegalArgumentException("orderedSlugs must not contain duplicate slugs");
        }
        lockWorkspace(ctx);
        List<Practice> bucket = practiceRepository.findAllForCatalog(ctx.id()).stream()
                .filter(p -> Objects.equals(
                        groupSlug, p.getGroup() == null ? null : p.getGroup().getSlug()))
                .toList();
        Set<String> existing = bucket.stream().map(Practice::getSlug).collect(Collectors.toSet());
        Set<String> requested = new HashSet<>(orderedSlugs);
        if (!existing.equals(requested)) {
            String unknown = requested.stream()
                    .filter(s -> !existing.contains(s))
                    .findFirst()
                    .orElse(null);
            if (unknown != null) {
                throw new EntityNotFoundException("Practice", unknown);
            }
            throw new IllegalArgumentException(
                    "orderedSlugs must contain every practice in the group (a complete ordering)");
        }
        Map<String, Practice> bySlug = bucket.stream().collect(Collectors.toMap(Practice::getSlug, p -> p));
        int order = 0;
        for (String slug : orderedSlugs) {
            Practice p = Objects.requireNonNull(bySlug.get(slug));
            p.setDisplayOrder(order++);
            practiceRepository.save(p);
        }
    }

    @Transactional
    public List<Practice> placePractice(
            WorkspaceContext ctx, String practiceSlug, @Nullable String groupSlug, int position) {
        lockWorkspace(ctx);
        Practice practice = practiceRepository
                .findByWorkspaceIdAndSlug(ctx.id(), practiceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", practiceSlug));
        PracticeDefinitionSnapshot before = PracticeDefinitionSnapshot.of(practice, currentRevisionNumber(practice));
        PracticeGroup destination = groupSlug == null
                ? null
                : practiceGroupRepository
                        .findByWorkspaceIdAndSlug(ctx.id(), groupSlug)
                        .orElseThrow(() -> new EntityNotFoundException("PracticeGroup", groupSlug));

        Long sourceGroupId =
                practice.getGroup() == null ? null : practice.getGroup().getId();
        Long destinationGroupId = destination == null ? null : destination.getId();
        List<Practice> allPractices = practiceRepository.findAllForCatalog(ctx.id());
        List<Practice> source = practicesInGroup(allPractices, sourceGroupId, practice);
        List<Practice> target = Objects.equals(sourceGroupId, destinationGroupId)
                ? source
                : practicesInGroup(allPractices, destinationGroupId, practice);

        if (position > target.size()) {
            throw new IllegalArgumentException("position exceeds the destination size");
        }
        target.add(position, practice);
        practice.setGroup(destination);
        resequence(target);
        if (!Objects.equals(sourceGroupId, destinationGroupId)) {
            resequence(source);
            int revisionNumber = practiceRevisionService.append(practice).getRevisionNumber();
            configAudit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.PRACTICE_DEFINITION,
                    practice.getId(),
                    ctx.id(),
                    before,
                    PracticeDefinitionSnapshot.of(practice, revisionNumber)));
        }
        return allPractices;
    }

    private List<Practice> practicesInGroup(List<Practice> allPractices, @Nullable Long groupId, Practice excluded) {
        return allPractices.stream()
                .filter(practice -> Objects.equals(
                        groupId,
                        practice.getGroup() == null ? null : practice.getGroup().getId()))
                .filter(practice -> !practice.getId().equals(excluded.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void resequence(List<Practice> practices) {
        for (int index = 0; index < practices.size(); index++) {
            practices.get(index).setDisplayOrder(index);
        }
        practiceRepository.saveAll(practices);
    }

    @Transactional(readOnly = true)
    public Practice getPractice(WorkspaceContext ctx, String slug) {
        return practiceRepository
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));
    }

    @Transactional
    public Practice createPractice(WorkspaceContext ctx, CreatePracticeRequestDTO request) {
        return createPractice(ctx, required(request.slug(), "slug"), definition(request), null, null, null);
    }

    @Transactional
    public Practice createPracticeFromCatalog(WorkspaceContext ctx, String slug, PracticeDefinition definition) {
        return createPractice(ctx, slug, definition, slug, definition.provenanceFingerprint(slug), null);
    }

    @Transactional
    public Practice adoptPracticeFromCatalog(
            WorkspaceContext ctx, String slug, PracticeDefinition definition, PracticeAutonomy initialAutonomy) {
        return createPractice(ctx, slug, definition, slug, definition.provenanceFingerprint(slug), initialAutonomy);
    }

    private Practice createPractice(
            WorkspaceContext ctx,
            String slug,
            PracticeDefinition definition,
            @Nullable String sourceCuratedSlug,
            @Nullable String sourceCuratedFingerprint,
            @Nullable PracticeAutonomy initialAutonomy) {
        if (practiceRepository.existsByWorkspaceIdAndSlug(ctx.id(), slug)) {
            throw new PracticeSlugConflictException(
                    "A practice with slug '" + slug + "' already exists in this workspace.");
        }

        Workspace workspace = lockWorkspace(ctx);

        Practice practice = new Practice();
        String groupSlug = definition.groupSlug();
        var group = groupSlug == null
                ? null
                : practiceGroupRepository
                        .findByWorkspaceIdAndSlug(ctx.id(), groupSlug)
                        .orElseThrow(() -> new EntityNotFoundException("PracticeGroup", groupSlug));
        practice.setWorkspace(workspace);
        practice.setSourceCuratedSlug(sourceCuratedSlug);
        practice.setSourceCuratedFingerprint(sourceCuratedFingerprint);
        if (sourceCuratedSlug != null) {
            practice.setAdoptedBase(definition);
            practice.setAdoptedBaseSource(AdoptedBaseSource.EXACT_ADOPTION);
        }
        practice.setGroup(group);
        practice.setDisplayOrder(
                practiceRepository.findMaxDisplayOrder(ctx.id(), group == null ? null : group.getId()) + 1);
        practice.setSlug(slug);
        applyDefinition(practice, definition);
        // A new practice holds no opinion of its own and inherits its group's (and through it the
        // workspace's) — stamping the resolved default here would give every practice an opinion nobody
        // expressed. Exception: a practice whose policy cannot attempt automated review is written OFF
        // explicitly, since that's a fact about the practice, not a preference to inherit over.
        practice.setAutonomy(
                definition.automatedReviewPolicy().automatedReview().canAttemptAutomatedReview()
                        ? initialAutonomy
                        : PracticeAutonomy.OFF);
        definitionValidator.validate(definition);

        try {
            practice = practiceRepository.save(practice);
        } catch (DataIntegrityViolationException ex) {
            if (!DataIntegrityViolationConstraints.hasName(ex, "uk_practice_workspace_slug")) {
                throw ex;
            }
            throw new PracticeSlugConflictException(
                    "A practice with slug '" + slug + "' already exists in this workspace.", ex);
        }
        int revisionNumber = practiceRevisionService.append(practice).getRevisionNumber();
        workspace.getReviewSettings().incrementRolloutRevision();
        configAudit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                PracticeDefinitionSnapshot.of(practice, revisionNumber)));
        if (initialAutonomy != null) {
            configAudit.record(ConfigAuditEntry.created(
                    ConfigAuditEntityType.PRACTICE_USAGE,
                    practice.getId(),
                    ctx.id(),
                    new PracticeUsageSnapshot(practice.getAutonomy())));
        }

        log.info("Created practice '{}' (slug={}) in workspace {}", practice.getName(), practice.getSlug(), ctx.slug());
        return practice;
    }

    private Workspace lockWorkspace(WorkspaceContext ctx) {
        return workspaceRepository
                .findByIdForUpdate(ctx.id())
                .orElseThrow(() -> new EntityNotFoundException("Workspace", ctx.slug()));
    }

    @Transactional
    public Practice updatePractice(WorkspaceContext ctx, String slug, UpdatePracticeRequestDTO request) {
        Workspace workspace = lockWorkspace(ctx);
        Practice practice = practiceRepository
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));
        Integer revisionNumber = currentRevisionNumber(practice);
        PracticeDefinitionSnapshot before = PracticeDefinitionSnapshot.of(practice, revisionNumber);
        PracticeDefinition beforeDefinition = PracticeDefinition.from(practice);
        PracticeAutonomy effectiveAutonomyBefore = effectiveAutonomy(practice, ctx.id());

        Set<ClearablePracticeField> fieldsToClear = request.clear() == null ? Set.of() : request.clear();
        List<PracticeBinding> bindings = request.bindings() == null ? beforeDefinition.bindings() : request.bindings();
        BindingChange.requireExplicit(beforeDefinition.bindings(), bindings, request.bindingChanges());
        // The kind is read off the bindings, so "the author moved this practice to another kind of
        // work" is a question about the new bindings rather than a separate field to compare.
        ArtifactKind artifactKind = PracticeBinding.artifactKindOf(bindings);
        PracticeAutomatedReviewPolicy automatedReviewPolicy = request.automatedReviewPolicy() != null
                ? request.automatedReviewPolicy()
                : artifactKind.equals(beforeDefinition.artifactKind())
                        ? beforeDefinition.automatedReviewPolicy()
                        : evidenceDefaults.policyFor(artifactKind);
        boolean removesAutomatedReview = request.automatedReviewPolicy() != null
                && !automatedReviewPolicy.automatedReview().canAttemptAutomatedReview();
        if (removesAutomatedReview) {
            // A practice nobody automates still says what occasions it — that is where its kind comes
            // from — but it reads nothing, so the evidence goes with the automation that read it.
            bindings = bindings.stream().map(PracticeService::withoutEvidence).toList();
        }
        PracticeDefinition afterDefinition = new PracticeDefinition(
                request.name() == null ? beforeDefinition.name() : request.name(),
                bindings,
                request.criteria() == null ? beforeDefinition.criteria() : request.criteria(),
                removesAutomatedReview && request.precomputeScript() == null
                        ? null
                        : patch(
                                beforeDefinition.precomputeScript(),
                                request.precomputeScript(),
                                fieldsToClear.contains(ClearablePracticeField.PRECOMPUTE_SCRIPT)),
                automatedReviewPolicy,
                patch(
                        beforeDefinition.whyItMatters(),
                        request.whyItMatters(),
                        fieldsToClear.contains(ClearablePracticeField.WHY_IT_MATTERS)),
                patch(
                        beforeDefinition.whatGoodLooksLike(),
                        request.whatGoodLooksLike(),
                        fieldsToClear.contains(ClearablePracticeField.WHAT_GOOD_LOOKS_LIKE)),
                request.group() == null
                        ? beforeDefinition.groupSlug()
                        : request.group().groupSlug(),
                request.deliveryBehavior() == null ? beforeDefinition.deliveryBehavior() : request.deliveryBehavior());

        if (afterDefinition.equals(beforeDefinition)) {
            return practice;
        }

        if (request.group() != null) {
            practiceGroupService.applyBinding(ctx, practice, request.group().groupSlug());
        }
        PracticeAutonomy autonomyBefore = practice.getAutonomy();
        applyDefinition(practice, afterDefinition);
        if (!afterDefinition.automatedReviewPolicy().automatedReview().canAttemptAutomatedReview()) {
            practice.setAutonomy(PracticeAutonomy.OFF);
        }
        validateUpdate(afterDefinition, request.bindings() != null);
        practice = practiceRepository.save(practice);
        revisionNumber = practiceRevisionService.append(practice).getRevisionNumber();
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_DEFINITION,
                practice.getId(),
                ctx.id(),
                before,
                PracticeDefinitionSnapshot.of(practice, revisionNumber)));
        if (autonomyBefore != practice.getAutonomy()) {
            configAudit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.PRACTICE_USAGE,
                    practice.getId(),
                    ctx.id(),
                    new PracticeUsageSnapshot(autonomyBefore),
                    new PracticeUsageSnapshot(practice.getAutonomy())));
        }
        if (effectiveAutonomyBefore != effectiveAutonomy(practice, ctx.id())) {
            workspace.getReviewSettings().incrementRolloutRevision();
        }
        log.info("Updated practice '{}' (slug={}) in workspace {}", practice.getName(), slug, ctx.slug());
        return practice;
    }

    /**
     * Sets one practice's own autonomy, or clears it back to inherit.
     *
     * @param autonomy the autonomy to hold, or {@code null} to hold none and inherit the group's — and through
     *     it the workspace's. Clearing has to be expressible or the chain is write-once: an administrator
     *     who set one practice explicitly could never put it back under the group's decision.
     */
    @Transactional
    public Practice setAutonomy(WorkspaceContext ctx, String slug, @Nullable PracticeAutonomy autonomy) {
        Workspace workspace = lockWorkspace(ctx);
        Practice practice = practiceRepository
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));

        PracticeAutonomy before = practice.getAutonomy();
        if (before == autonomy) {
            return practice;
        }
        PracticeAutonomy effectiveBefore = effectiveAutonomy(practice, ctx.id());
        // Every autonomy above OFF starts a review, so every autonomy above OFF needs a policy that can run one.
        // Asked of the autonomy that would be IN FORCE, not of the one being written: "inherit" is a request
        // for whatever the group says, and if that admits a review the practice still cannot run it.
        PracticeAutonomy effective = AutonomyResolver.resolvePractice(
                        autonomy,
                        practice.getGroup() == null ? null : practice.getGroup().getAutonomy(),
                        workspaceDefaults.forWorkspace(ctx.id()).defaultAutonomy())
                .autonomy();
        if (effective.admitsReview()
                && !practice.getAutomatedReviewPolicy().automatedReview().canAttemptAutomatedReview()) {
            throw new IllegalArgumentException(
                    "This practice cannot be used in automated reviews with its current review settings");
        }

        practice.setAutonomy(autonomy);
        practice = practiceRepository.save(practice);
        if (effectiveBefore != effectiveAutonomy(practice, ctx.id())) {
            workspace.getReviewSettings().incrementRolloutRevision();
        }
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.PRACTICE_USAGE,
                practice.getId(),
                ctx.id(),
                new PracticeUsageSnapshot(before),
                new PracticeUsageSnapshot(autonomy)));
        log.info(
                "Set practice '{}' (slug={}) autonomy={} in workspace {}",
                practice.getName(),
                slug,
                autonomy,
                ctx.slug());
        return practice;
    }

    private PracticeAutonomy effectiveAutonomy(Practice practice, Long workspaceId) {
        return AutonomyResolver.effectiveAutonomyOf(
                practice, workspaceDefaults.forWorkspace(workspaceId).defaultAutonomy());
    }

    @Transactional
    public void deletePractice(WorkspaceContext ctx, String slug) {
        Workspace workspace = lockWorkspace(ctx);
        Practice practice = practiceRepository
                .findByWorkspaceIdAndSlug(ctx.id(), slug)
                .orElseThrow(() -> new EntityNotFoundException("Practice", slug));

        Long practiceId = practice.getId();
        PracticeDefinitionSnapshot before = PracticeDefinitionSnapshot.of(practice, currentRevisionNumber(practice));
        practiceRepository.delete(practice);
        workspace.getReviewSettings().incrementRolloutRevision();
        configAudit.record(
                ConfigAuditEntry.deleted(ConfigAuditEntityType.PRACTICE_DEFINITION, practiceId, ctx.id(), before));
        log.info("Deleted practice '{}' (slug={}) from workspace {}", practice.getName(), slug, ctx.slug());
    }

    private @Nullable Integer currentRevisionNumber(Practice practice) {
        return practiceRevisionService.currentRevisionNumber(practice);
    }

    private PracticeDefinition definition(CreatePracticeRequestDTO request) {
        List<PracticeBinding> bindings = required(request.bindings(), "bindings");
        ArtifactKind artifactKind = PracticeBinding.artifactKindOf(bindings);
        return new PracticeDefinition(
                required(request.name(), "name"),
                bindings,
                required(request.criteria(), "criteria"),
                request.precomputeScript(),
                request.automatedReviewPolicy() == null
                        ? evidenceDefaults.policyFor(artifactKind)
                        : request.automatedReviewPolicy(),
                request.whyItMatters(),
                request.whatGoodLooksLike(),
                request.groupSlug(),
                request.deliveryBehavior() == null ? PracticeDeliveryBehavior.DEFAULT : request.deliveryBehavior());
    }

    private static <T> T required(@Nullable T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /**
     * Validates an edit, holding the single-occasion rule to what the caller actually said.
     *
     * <p>A practice reviewed on one occasion is a rule about the occasion somebody <em>submits</em>. An
     * update that omits {@code bindings} makes no statement about occasions at all — a rename is the
     * plainest example — so carrying the stored occasion forward and then refusing it would leave a
     * practice written while two were still legal impossible to edit ever again, by anyone, in any
     * field. Refusing the caller's own second occasion still happens, in the same words, both here and
     * in bean validation on the request.
     *
     * <p>Carried-over occasions are not waved through: they are validated one at a time, because this
     * same request can move the review policy they hang off — a different source-contract version can
     * retire a source an untouched occasion reads. Only the count is a property of the list; every
     * other rule the validator applies is a property of a single occasion, so checking each alone is
     * the same coverage minus exactly the rule that does not apply.
     */
    private void validateUpdate(PracticeDefinition afterDefinition, boolean occasionSubmitted) {
        if (occasionSubmitted || afterDefinition.bindings().size() <= 1) {
            definitionValidator.validate(afterDefinition);
            return;
        }
        for (PracticeBinding carried : afterDefinition.bindings()) {
            definitionValidator.validate(withBindings(afterDefinition, List.of(carried)));
        }
    }

    private static PracticeDefinition withBindings(PracticeDefinition definition, List<PracticeBinding> bindings) {
        return new PracticeDefinition(
                definition.name(),
                bindings,
                definition.criteria(),
                definition.precomputeScript(),
                definition.automatedReviewPolicy(),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                definition.groupSlug(),
                definition.deliveryBehavior());
    }

    private static PracticeBinding withoutEvidence(PracticeBinding binding) {
        return new PracticeBinding(
                binding.signals(), List.of(), binding.onDrafts(), binding.subject(), binding.appliesWhen());
    }

    static void applyDefinition(Practice practice, PracticeDefinition definition) {
        practice.setName(definition.name());
        practice.setBindings(definition.bindings());
        practice.setCriteria(definition.criteria());
        practice.setPrecomputeScript(definition.precomputeScript());
        practice.setAutomatedReviewPolicy(definition.automatedReviewPolicy());
        practice.setDeliveryBehavior(definition.deliveryBehavior());
        practice.setWhyItMatters(definition.whyItMatters());
        practice.setWhatGoodLooksLike(definition.whatGoodLooksLike());
    }

    private static @Nullable String patch(@Nullable String current, @Nullable String replacement, boolean clear) {
        return replacement != null ? replacement : clear ? null : current;
    }
}
