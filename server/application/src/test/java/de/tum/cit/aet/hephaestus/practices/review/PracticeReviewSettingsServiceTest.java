package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewVolumeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewPersonMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class PracticeReviewSettingsServiceTest extends BaseUnitTest {

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConfigAuditPort configAudit;

    @Mock
    private PracticeReviewCoverageService coverageService;

    @Mock
    private PracticeReviewVolumeQuery volumeQuery;

    private PracticeReviewSettingsService service;
    private Workspace workspace;
    private WorkspaceContext context;

    private final PracticeReviewProperties reviewProperties = new PracticeReviewProperties(false, 15, 5, false, null);

    @BeforeEach
    void setUp() {
        service = new PracticeReviewSettingsService(
                workspaceRepository, reviewProperties, configAudit, coverageService, volumeQuery);
        workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("ws");
        context = new WorkspaceContext(1L, "ws", "Ws", AccountType.ORG, null, false, false, Set.of());
        lenient().when(coverageService.scope(workspace)).thenReturn(WorkspaceReviewScope.ALL);
        lenient()
                .when(coverageService.summary(workspace, 0))
                .thenReturn(new PracticeReviewCoverageSummaryDTO(0, 0, 0, 0, 0, 30));
    }

    @Test
    void getSettingsReturnsEffectiveValuesAndRevisionEtag() {
        readsWorkspace();

        PracticeReviewSettingsDTO view = service.getSettings(context);

        assertThat(view.cooldownMinutes()).isEqualTo(15);
        assertThat(view.cooldownMinutesOverride()).isNull();
        assertThat(view.etag()).isEqualTo("\"0\"");
        assertThat(view.revision()).isZero();
    }

    @Test
    void updateRequiresIfMatch() {
        writesWorkspace();

        assertThatThrownBy(() -> service.updatePracticeReview(context, patch(true, 30), null))
                .isInstanceOf(PracticeReviewPreconditionRequiredException.class);
    }

    @Test
    void staleEtagCannotOverwriteAConcurrentChange() {
        writesWorkspace();
        workspace.getReviewSettings().incrementConfigVersion();

        assertThatThrownBy(() -> service.updatePracticeReview(context, patch(true, 30), tag(0)))
                .isInstanceOf(StalePracticeReviewSettingsException.class);
        assertThat(workspace.getReviewSettings().getDeliverToMerged()).isNull();
    }

    @Test
    void updateAppliesPatchIncrementsRevisionAndAudits() {
        writesWorkspace();

        PracticeReviewSettingsDTO view = service.updatePracticeReview(context, patch(true, 30), tag(0));

        assertThat(workspace.getReviewSettings().getDeliverToMerged()).isTrue();
        assertThat(workspace.getReviewSettings().getCooldownMinutes()).isEqualTo(30);
        assertThat(view.revision()).isEqualTo(1);
        assertThat(view.etag()).isEqualTo("\"1\"");
        verify(configAudit).record(any());
    }

    @Test
    void noOpUpdateChangesEtagWithoutInvalidatingAdmittedJobs() {
        writesWorkspace();

        PracticeReviewSettingsDTO view = service.updatePracticeReview(
                context, new UpdatePracticeReviewSettingsRequestDTO(null, null, null, null, null, null), tag(0));

        assertThat(view.revision()).isZero();
        assertThat(view.etag()).isEqualTo("\"1\"");
    }

    @Test
    void cooldownUpdateDoesNotInvalidateAdmittedJobs() {
        writesWorkspace();

        PracticeReviewSettingsDTO view = service.updatePracticeReview(context, patch(null, 30), tag(0));

        assertThat(view.revision()).isZero();
        assertThat(view.etag()).isEqualTo("\"1\"");
    }

    @Test
    void coverageIsReplacedWholesaleAndItsModesBecomeCurrent() {
        writesWorkspace();
        WorkspaceReviewScope selectedEmpty = new WorkspaceReviewScope(
                ReviewRepositoryMode.SELECTED, ReviewPersonMode.SELECTED, java.util.List.of(), java.util.List.of());
        when(coverageService.scope(workspace)).thenReturn(WorkspaceReviewScope.ALL, selectedEmpty, selectedEmpty);

        PracticeReviewSettingsDTO view = service.updatePracticeReview(
                context,
                new UpdatePracticeReviewSettingsRequestDTO(null, null, selectedEmpty, null, null, null),
                tag(0));

        verify(coverageService).replace(workspace, selectedEmpty);
        assertThat(view.reviewScope()).isEqualTo(selectedEmpty);
    }

    @Test
    void pausingAndResumingEachInvalidateWorkAlreadyInFlight() {
        writesWorkspace();

        service.updatePracticeReview(
                context,
                new UpdatePracticeReviewSettingsRequestDTO(null, null, null, PracticeDeliveryStatus.PAUSED, null, null),
                tag(0));
        service.updatePracticeReview(
                context,
                new UpdatePracticeReviewSettingsRequestDTO(null, null, null, PracticeDeliveryStatus.ACTIVE, null, null),
                tag(1));

        assertThat(workspace.getReviewSettings().getDeliveryStatus()).isEqualTo(PracticeDeliveryStatus.ACTIVE);
        assertThat(workspace.getReviewSettings().getRolloutRevision()).isEqualTo(2);
    }

    @Test
    void removingAndReaddingCoverageCannotMakeEarlierWorkCurrentAgain() {
        writesWorkspace();
        WorkspaceReviewScope selectedEmpty = new WorkspaceReviewScope(
                ReviewRepositoryMode.SELECTED, ReviewPersonMode.SELECTED, java.util.List.of(), java.util.List.of());
        when(coverageService.scope(workspace))
                .thenReturn(
                        WorkspaceReviewScope.ALL,
                        selectedEmpty,
                        selectedEmpty,
                        selectedEmpty,
                        WorkspaceReviewScope.ALL,
                        WorkspaceReviewScope.ALL);

        service.updatePracticeReview(
                context,
                new UpdatePracticeReviewSettingsRequestDTO(null, null, selectedEmpty, null, null, null),
                tag(0));
        service.updatePracticeReview(
                context,
                new UpdatePracticeReviewSettingsRequestDTO(null, null, WorkspaceReviewScope.ALL, null, null, null),
                tag(1));

        assertThat(workspace.getReviewSettings().getRolloutRevision()).isEqualTo(2);
    }

    @Test
    void promotingDefaultAuthorityInvalidatesEarlierWork() {
        writesWorkspace();

        service.updatePracticeReview(
                context,
                new UpdatePracticeReviewSettingsRequestDTO(null, null, null, null, PracticeAutonomy.AUTOMATIC, null),
                tag(0));

        assertThat(workspace.getReviewSettings().getRolloutRevision()).isOne();
    }

    private void readsWorkspace() {
        when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
    }

    private void writesWorkspace() {
        when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        lenient().when(workspaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static UpdatePracticeReviewSettingsRequestDTO patch(
            @Nullable Boolean deliverToMerged, @Nullable Integer cooldownMinutes) {
        return new UpdatePracticeReviewSettingsRequestDTO(deliverToMerged, cooldownMinutes, null, null, null, null);
    }

    private static EntityTagPrecondition tag(long revision) {
        return EntityTagPrecondition.parse("\"" + revision + "\"");
    }
}
