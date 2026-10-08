package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogService;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseFieldDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class PracticeReleaseServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String SLUG = "describe-what-and-why";
    private static final String GUIDED = "scope-one-reviewable-change";

    @Autowired
    private PracticeReleaseService releases;

    @Autowired
    private CuratedCatalogService catalog;

    @Autowired
    private PracticeService practiceService;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private PracticeRevisionRepository revisions;

    @Autowired
    private PracticeGroupRepository groups;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void acceptanceMergesFieldsAndCreatesRevisionOnlyForTheAcceptingWorkspace() {
        WorkspaceContext first = workspace("first-release");
        WorkspaceContext second = workspace("second-release");
        adoptOldVersion(first);
        adoptOldVersion(second);

        PracticeReleaseProposalDTO proposal = releases.get(first, SLUG);
        assertThat(proposal.fields()).anySatisfy(field -> {
            assertThat(field.field()).isEqualTo(PracticeDefinitionField.CRITERIA);
            assertThat(field.conflict()).isTrue();
        });
        Practice before = practices.findByWorkspaceIdAndSlug(first.id(), SLUG).orElseThrow();
        int oldRevision = before.getCurrentRevision().getRevisionNumber();
        long revisionCount = revisionCountFor(before);

        releases.accept(
                first, SLUG, match(proposal), Map.of(PracticeDefinitionField.CRITERIA, PracticeReleaseChoice.OFFERED));

        Practice accepted = practices.findByWorkspaceIdAndSlug(first.id(), SLUG).orElseThrow();
        assertThat(accepted.getCriteria()).isEqualTo(proposal.offered().criteria());
        assertThat(accepted.getAdoptedBase()).isEqualTo(proposal.offered());
        assertThat(accepted.getCurrentRevision().getRevisionNumber()).isEqualTo(oldRevision + 1);
        assertThat(revisionCountFor(accepted)).isEqualTo(revisionCount + 1);
        assertThat(revisions.findAll().stream()
                        .filter(revision -> revision.getPractice().getId().equals(accepted.getId()))
                        .filter(revision -> revision.getRevisionNumber() == oldRevision)
                        .findFirst()
                        .orElseThrow()
                        .getCriteria())
                .isEqualTo("Earlier criteria");
        assertThat(releases.list(first)).isEmpty();
        assertThat(releases.list(second)).hasSize(1);
    }

    @Test
    void declineLeavesDefinitionAndRevisionUntouchedAndRejectsStaleOffer() {
        WorkspaceContext ctx = workspace("decline-release");
        adoptOldVersion(ctx);
        PracticeReleaseProposalDTO proposal = releases.get(ctx, SLUG);
        Practice before = practices.findByWorkspaceIdAndSlug(ctx.id(), SLUG).orElseThrow();
        long revisionCount = revisionCountFor(before);

        assertThatThrownBy(() -> releases.decline(ctx, SLUG, null))
                .isInstanceOf(PracticeReleasePreconditionRequiredException.class);
        assertThatThrownBy(() -> releases.decline(ctx, SLUG, EntityTagPrecondition.parse("\"stale\"")))
                .isInstanceOf(StalePracticeReleaseException.class);
        releases.decline(ctx, SLUG, match(proposal));

        Practice declined = practices.findByWorkspaceIdAndSlug(ctx.id(), SLUG).orElseThrow();
        assertThat(PracticeDefinition.from(declined)).isEqualTo(PracticeDefinition.from(before));
        assertThat(declined.getCurrentRevision().getRevisionNumber())
                .isEqualTo(before.getCurrentRevision().getRevisionNumber());
        assertThat(revisionCountFor(declined)).isEqualTo(revisionCount);
        assertThat(releases.list(ctx)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE workspace_id = ? AND entity_type = 'PRACTICE_DEFINITION' AND entity_id = ? AND action = 'UPDATED' AND changed_keys @> ARRAY['declinedOfferedDigest']::text[]",
                        Long.class,
                        ctx.id(),
                        declined.getId().toString()))
                .isEqualTo(1L);

        var entry = catalog.practice(SLUG);
        catalog.writePractice(
                SLUG,
                EntityTagPrecondition.parse("\"" + entry.etag() + "\""),
                withCriteria(entry.effective(), "Later catalog criteria"),
                null);
        assertThat(releases.list(ctx)).hasSize(1);
    }

    @Test
    void shouldApplyTheOfferedGuideAndKeepTheCurrentVisualWhenOnlyTheGuidanceChanged() {
        WorkspaceContext ctx = workspace("guidance-release");
        PracticeDefinition offered = catalog.practice(GUIDED).effective();
        var earlierVisual = new PracticeVisual(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 32\">"
                        + "<rect class=\"pv-fill-accent\" x=\"0\" y=\"0\" width=\"64\" height=\"32\"/></svg>",
                "One wide box.");
        var earlierGuide = new PracticeGuide("## Earlier guide\n\nSplit the work by goal.", Map.of());
        createGroup(ctx, offered);
        practiceService.createPracticeFromCatalog(ctx, GUIDED, withGuidance(offered, earlierVisual, earlierGuide));
        Practice before = practices.findByWorkspaceIdAndSlug(ctx.id(), GUIDED).orElseThrow();
        int oldRevision = before.getCurrentRevision().getRevisionNumber();
        String oldFingerprint = before.getCurrentRevision().getReviewRuleFingerprint();

        PracticeReleaseProposalDTO proposal = releases.get(ctx, GUIDED);
        assertThat(proposal.fields())
                .extracting(PracticeReleaseFieldDTO::field)
                .containsExactlyInAnyOrder(PracticeDefinitionField.VISUAL, PracticeDefinitionField.GUIDE);
        assertThat(proposal.fields()).noneMatch(PracticeReleaseFieldDTO::conflict);

        releases.accept(
                ctx,
                GUIDED,
                match(proposal),
                Map.of(
                        PracticeDefinitionField.GUIDE, PracticeReleaseChoice.OFFERED,
                        PracticeDefinitionField.VISUAL, PracticeReleaseChoice.CURRENT));

        Practice accepted = practices.findByWorkspaceIdAndSlug(ctx.id(), GUIDED).orElseThrow();
        assertThat(accepted.getGuide()).isEqualTo(offered.guide());
        assertThat(accepted.getVisual()).isEqualTo(earlierVisual);
        assertThat(accepted.getAdoptedBase()).isEqualTo(offered);
        var revision = accepted.getCurrentRevision();
        assertThat(revision.getRevisionNumber()).isEqualTo(oldRevision + 1);
        assertThat(revision.getGuide()).isEqualTo(offered.guide());
        assertThat(revision.getVisual()).isEqualTo(earlierVisual);
        assertThat(revision.getReviewRuleFingerprint()).isEqualTo(oldFingerprint);
        assertThat(releases.list(ctx)).isEmpty();
    }

    private WorkspaceContext workspace(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, slug, slug + "-org", AccountType.ORG, owner);
        return WorkspaceContext.fromWorkspace(workspace, Set.of(WorkspaceMembership.WorkspaceRole.ADMIN), null);
    }

    private void adoptOldVersion(WorkspaceContext ctx) {
        PracticeDefinition offered = catalog.practice(SLUG).effective();
        createGroup(ctx, offered);
        PracticeDefinition old = withCriteria(offered, "Earlier criteria");
        practiceService.createPracticeFromCatalog(ctx, SLUG, old);
        Practice practice = practices.findByWorkspaceIdAndSlug(ctx.id(), SLUG).orElseThrow();
        practice.setCriteria("Local criteria");
        practices.save(practice);
    }

    private void createGroup(WorkspaceContext ctx, PracticeDefinition offered) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspaces.findById(ctx.id()).orElseThrow());
        group.setSlug(Objects.requireNonNull(offered.groupSlug()));
        group.setName("Review ready work");
        groups.save(group);
    }

    private static EntityTagPrecondition match(PracticeReleaseProposalDTO proposal) {
        return EntityTagPrecondition.parse("\"" + proposal.etag() + "\"");
    }

    private static PracticeDefinition withCriteria(PracticeDefinition source, String criteria) {
        return new PracticeDefinition(
                source.name(),
                source.signals(),
                source.evidenceRequirements(),
                source.reviewWhen(),
                source.subject(),
                source.precondition(),
                criteria,
                source.precomputeScript(),
                source.automatedReviewPolicy(),
                source.whyItMatters(),
                source.whatGoodLooksLike(),
                source.groupSlug(),
                source.deliveryBehavior(),
                source.visual(),
                source.guide());
    }

    private static PracticeDefinition withGuidance(
            PracticeDefinition source, PracticeVisual visual, PracticeGuide guide) {
        return new PracticeDefinition(
                source.name(),
                source.signals(),
                source.evidenceRequirements(),
                source.reviewWhen(),
                source.subject(),
                source.precondition(),
                source.criteria(),
                source.precomputeScript(),
                source.automatedReviewPolicy(),
                source.whyItMatters(),
                source.whatGoodLooksLike(),
                source.groupSlug(),
                source.deliveryBehavior(),
                visual,
                guide);
    }

    private long revisionCountFor(Practice practice) {
        return revisions.findAll().stream()
                .filter(revision -> revision.getPractice().getId().equals(practice.getId()))
                .count();
    }
}
