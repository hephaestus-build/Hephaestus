package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.BindPracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.FeedbackModeRollupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.GroupRefDTO;
import de.tum.cit.aet.hephaestus.practices.dto.MovePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGuidanceDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReviewedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetFeedbackModeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeRollupService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptSummaries;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeScriptSummaryDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ETag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@RequestMapping("/practices")
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PracticeCatalogController {
    private final PracticeService practices;
    private final PracticeGroupService groups;
    private final PracticeVersionService versions;
    private final PracticeReleaseService releases;
    private final CatalogOriginPresenter presenter;
    private final PracticeDefinitionOptionsService options;
    private final FeedbackModeRollupService modes;
    private final PracticeScriptSummaries scripts;

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listPractices")
    public List<PracticeDTO> list(
            WorkspaceContext ctx, @RequestParam(required = false) @Nullable FeedbackMode feedbackMode) {
        return presenter.presentPractices(ctx.id(), practices.listPractices(ctx, feedbackMode));
    }

    @GetMapping("/{practiceSlug}")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getPractice")
    public PracticeDTO get(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        return presenter.present(ctx.id(), practices.getPractice(ctx, practiceSlug));
    }

    @PostMapping
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "createPractice")
    @ApiResponse(responseCode = "201", description = "Practice created")
    public ResponseEntity<PracticeDTO> create(
            WorkspaceContext ctx, @RequestBody @Valid CreatePracticeRequestDTO input) {
        var created = practices.createPractice(ctx, input);
        return ResponseEntity.created(URI.create("/api/workspaces/" + ctx.slug() + "/practices/" + created.getSlug()))
                .body(presenter.present(ctx.id(), created));
    }

    @PatchMapping("/{practiceSlug}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "updatePractice")
    public PracticeDTO update(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid UpdatePracticeRequestDTO input) {
        return presenter.present(ctx.id(), practices.updatePractice(ctx, practiceSlug, input));
    }

    @DeleteMapping("/{practiceSlug}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "deletePractice")
    @ApiResponse(responseCode = "204", description = "Practice deleted")
    public ResponseEntity<Void> delete(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        practices.deletePractice(ctx, practiceSlug);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{practiceSlug}/versions")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listPracticeVersions")
    public List<PracticeVersionDTO> history(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        return presenter.history(practices.getPractice(ctx, practiceSlug));
    }

    @GetMapping("/definition-options")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getPracticeDefinitionOptions")
    public PracticeDefinitionOptionsDTO options(WorkspaceContext ctx) {
        return options.options(ctx.id());
    }

    @GetMapping("/practice-script-needs")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listPracticeScriptNeeds")
    public List<PracticeScriptSummaryDTO> scriptNeeds(WorkspaceContext ctx) {
        return scripts.latestCurrent(ctx.id());
    }

    @GetMapping("/feedback-modes")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getFeedbackModeRollup")
    public FeedbackModeRollupDTO modes(WorkspaceContext ctx) {
        return modes.rollup(ctx.id());
    }

    @PatchMapping("/{practiceSlug}/feedback-mode")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_USAGE")
    @Operation(operationId = "setPracticeFeedbackMode")
    public PracticeDTO mode(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid SetFeedbackModeRequestDTO input) {
        return presenter.present(ctx.id(), practices.setFeedbackMode(ctx, practiceSlug, input.feedbackMode()));
    }

    @PutMapping("/{practiceSlug}/group")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "bindPracticeGroup")
    public PracticeDTO bind(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid BindPracticeGroupRequestDTO input) {
        return presenter.present(ctx.id(), groups.bindPractice(ctx, practiceSlug, input.groupSlug()));
    }

    @PatchMapping("/{practiceSlug}/group")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "movePractice")
    public List<PracticeDTO> move(
            WorkspaceContext ctx, @PathVariable String practiceSlug, @RequestBody @Valid MovePracticeRequestDTO input) {
        return presenter.presentPractices(
                ctx.id(), practices.movePractice(ctx, practiceSlug, input.groupSlug(), input.position()));
    }

    @DeleteMapping("/{practiceSlug}/catalog-customization")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "resetPracticeToCatalog")
    public PracticeDTO reset(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        return presenter.present(
                ctx.id(),
                releases.reset(ctx, practiceSlug, ifMatch == null ? null : EntityTagPrecondition.parse(ifMatch)));
    }

    @GetMapping("/reviewed")
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "listReviewedPractices")
    public List<ReviewedPracticeDTO> reviewed(WorkspaceContext ctx) {
        return practices.listReviewedPractices(ctx).stream()
                .map(practice -> {
                    var definition = versions.definitionOf(practice.getCurrentVersion());
                    var group = practice.getGroup();
                    return new ReviewedPracticeDTO(
                            practice.getSlug(),
                            definition.name(),
                            group == null
                                    ? null
                                    : new GroupRefDTO(
                                            group.getSlug(), group.getName(), group.getIcon(), group.getColor()),
                            definition.guidance().whyItMatters(),
                            definition.guidance().whatGoodLooksLike());
                })
                .toList();
    }

    @GetMapping("/{practiceSlug}/guidance")
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(operationId = "getPracticeGuidance")
    @ApiResponse(responseCode = "200", description = "Practice guidance")
    @ApiResponse(responseCode = "304", description = "Guidance is unchanged", content = @Content)
    public ResponseEntity<PracticeGuidanceDTO> guidance(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestHeader(name = "If-None-Match", required = false) @Nullable String ifNoneMatch) {
        var value = versions.definitionOf(
                        practices.getPractice(ctx, practiceSlug).getCurrentVersion())
                .guidance();
        String tag = new CanonicalDigest()
                .addNullable(value.visual() == null ? null : value.visual().digest())
                .addNullable(value.guide() == null ? null : value.guide().digest())
                .hex();
        if (ifNoneMatch != null
                && EntityTagPrecondition.parse(ifNoneMatch).candidates().stream()
                        .anyMatch(
                                candidate -> candidate.isWildcard() || candidate.compare(new ETag(tag, false), false)))
            return ResponseEntity.status(304).eTag(tag).build();
        return ResponseEntity.ok().eTag(tag).body(new PracticeGuidanceDTO(practiceSlug, value.visual(), value.guide()));
    }
}
