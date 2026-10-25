package de.tum.cit.aet.hephaestus.practices.review.feedbackmode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.GroupAttributes;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class FeedbackModeReviewIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultStore resultStore;

    @Autowired
    ResultRepository results;

    @MockitoBean
    ReviewAttemptGuard ownership;

    @MockitoBean
    ReviewRuntimeQuery runtime;

    @MockitoBean
    ReviewModelBindings models;

    @MockitoBean
    ReviewBudget budget;

    @MockitoBean
    HistoryCustodyQuery custody;

    @Autowired
    CatalogOriginPresenter presenter;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeRepository practices;

    @Autowired
    PracticeService service;

    @Autowired
    PracticeGroupService groups;

    @Autowired
    WorkspaceRepository workspaces;

    @Test
    void shouldKeepReviewEligibilityWhenFeedbackIsOff() {
        var owner = persistUser("mode-owner");
        var workspace = createWorkspace("mode-off", "Modes", "mode-org", AccountType.ORG, owner);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "off-review",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                null,
                0,
                null));
        practice.setFeedbackMode(FeedbackMode.OFF);
        practices.saveAndFlush(practice);
        assertThat(service.listReviewedPractices(WorkspaceContext.fromWorkspace(workspace, Set.of(), null)))
                .extracting(Practice::getId)
                .contains(practice.getId());
    }

    @Test
    void shouldApplyAndClearEveryModeOverrideWithoutChangingThePracticeVersion() {
        var owner = persistUser("mode-owner");
        var workspace = createWorkspace("mode-inherit", "Modes", "mode-inherit-org", AccountType.ORG, owner);
        workspace.getReviewSettings().setDefaultFeedbackMode("AUTOMATIC");
        workspaces.saveAndFlush(workspace);
        var ctx = WorkspaceContext.fromWorkspace(workspace, Set.of(), null);
        var group = groups.createGroup(ctx, "mode-group", new GroupAttributes("Modes", null, null, null, null), true);
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace,
                "inherited-review",
                new ItemKind("scm.pull_request"),
                DefinitionTestFixtures.definition(),
                group.getId(),
                0,
                null));
        var versionId = practice.getCurrentVersion().getId();
        assertThat(FeedbackModeResolver.resolvePractice(practice, FeedbackMode.AUTOMATIC)
                        .mode())
                .isEqualTo(FeedbackMode.AUTOMATIC);
        groups.setFeedbackMode(ctx, group.getSlug(), FeedbackMode.OFF);
        var reloaded = service.getPractice(ctx, practice.getSlug());
        assertThat(FeedbackModeResolver.resolvePractice(reloaded, FeedbackMode.AUTOMATIC)
                        .mode())
                .isEqualTo(FeedbackMode.OFF);
        service.setFeedbackMode(ctx, practice.getSlug(), FeedbackMode.APPROVE_FIRST);
        reloaded = service.getPractice(ctx, practice.getSlug());
        assertThat(FeedbackModeResolver.resolvePractice(reloaded, FeedbackMode.AUTOMATIC)
                        .mode())
                .isEqualTo(FeedbackMode.APPROVE_FIRST);
        service.setFeedbackMode(ctx, practice.getSlug(), null);
        groups.setFeedbackMode(ctx, group.getSlug(), null);
        reloaded = service.getPractice(ctx, practice.getSlug());
        assertThat(FeedbackModeResolver.resolvePractice(reloaded, FeedbackMode.AUTOMATIC)
                        .mode())
                .isEqualTo(FeedbackMode.AUTOMATIC);
        assertThat(reloaded.getCurrentVersion().getId()).isEqualTo(versionId);
        assertThat(presenter.history(reloaded)).hasSize(1);
    }

    @Test
    void shouldPersistAnAdmittedResultWhenFeedbackModeIsOff() {
        var scenario = data.scenario();
        var ctx = WorkspaceContext.fromWorkspace(scenario.workspace(), Set.of(), null);
        service.setFeedbackMode(ctx, scenario.practice().getSlug(), FeedbackMode.OFF);
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        var workspaceId = scenario.workspace().getId();
        when(runtime.runtime(workspaceId, pending.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        1,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "mode-test")))));
        when(custody.find(workspaceId, List.of(pending.jobId())))
                .thenReturn(new HistoryCustody(true, false, List.of(), List.of(), List.of(pending.jobId())));
        var receipt = resultStore.admit(
                workspaceId,
                pending.review().getId(),
                pending.jobId(),
                1,
                List.of(admissions.candidate(pending, ResultOutcome.NOT_MET)));
        assertThat(receipt.withheldItems()).isEmpty();
        var recorded = results.findByIdAndWorkspaceId(receipt.resultIds().getFirst(), workspaceId)
                .orElseThrow();
        assertThat(recorded.getPracticeId()).isEqualTo(scenario.practice().getId());
        assertThat(recorded.getOutcome()).isEqualTo(ResultOutcome.NOT_MET);
        assertThat(service.getPractice(ctx, scenario.practice().getSlug()).getFeedbackMode())
                .isEqualTo(FeedbackMode.OFF);
    }
}
