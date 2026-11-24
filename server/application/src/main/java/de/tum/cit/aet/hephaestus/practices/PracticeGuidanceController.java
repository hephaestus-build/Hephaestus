package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.GroupRefDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGuidanceDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReviewedPracticeDTO;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ETag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/** What a member reads about the workspace's practices: those that reviews measure, and one practice's guidance. */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices")
@RequiredArgsConstructor
@ConditionalOnServerRole
@Tag(name = "practice-catalog")
public class PracticeGuidanceController {
    private final PracticeService practices;
    private final PracticeVersionService versions;

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
