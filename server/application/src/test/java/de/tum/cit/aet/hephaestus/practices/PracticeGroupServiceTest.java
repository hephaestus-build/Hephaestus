package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class PracticeGroupServiceTest extends BaseUnitTest {

    @Mock
    private PracticeGroupRepository practiceGroupRepository;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private PracticeRevisionService practiceRevisionService;

    @Mock
    private ConfigAuditPort configAudit;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @InjectMocks
    private PracticeGroupService service;

    private Workspace workspace;

    private static final WorkspaceContext CTX =
            new WorkspaceContext(1L, "acme", "Acme", AccountType.ORG, null, false, Set.of());

    @BeforeEach
    void mockWorkspaceLock() {
        workspace = new Workspace();
        workspace.setId(1L);
        lenient().when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        PracticeRevision revision = mock(PracticeRevision.class);
        lenient().when(revision.getRevisionNumber()).thenReturn(1);
        lenient().when(practiceRevisionService.append(any())).thenReturn(revision);
    }

    @Test
    void createGroup_persistsWithFields() {
        when(practiceGroupRepository.existsByWorkspaceIdAndSlug(1L, "review-comms"))
                .thenReturn(false);
        when(practiceGroupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PracticeGroup created = service.createGroup(
                CTX,
                "review-comms",
                new GroupAttributes("Effective review communication", "blurb", 0, "MessageSquareReply", "cyan"));

        assertThat(created.getSlug()).isEqualTo("review-comms");
        assertThat(created.getName()).isEqualTo("Effective review communication");
        assertThat(created.getDescription()).isEqualTo("blurb");
        assertThat(created.getIcon()).isEqualTo("MessageSquareReply");
        assertThat(created.getColor()).isEqualTo("cyan");
        assertThat(created.isVisibleInPracticeDashboards()).isTrue();
        verify(practiceGroupRepository).save(any(PracticeGroup.class));
    }

    @Test
    void createGroup_recordsItsConfiguration() {
        when(practiceGroupRepository.save(any())).thenAnswer(invocation -> {
            PracticeGroup group = invocation.getArgument(0);
            group.setId(9L);
            return group;
        });

        service.createGroup(CTX, "review-comms", new GroupAttributes("Review communication", null, 0, null, null));

        ConfigAuditEntry entry = capturedAuditEntry();
        assertThat(entry.entityType()).isEqualTo(ConfigAuditEntityType.PRACTICE_GROUP);
        assertThat(entry.entityId()).isEqualTo("9");
        assertThat(entry.workspaceId()).isEqualTo(1L);
        assertThat(entry.before()).isNull();
        assertThat(entry.after())
                .isEqualTo(new PracticeGroupSnapshot(
                        "review-comms", "Review communication", null, true, null, null, null));
    }

    @Test
    void createGroup_duplicateSlug_throwsConflict() {
        when(practiceGroupRepository.existsByWorkspaceIdAndSlug(1L, "dup")).thenReturn(true);

        assertThatExceptionOfType(PracticeGroupSlugConflictException.class)
                .isThrownBy(() -> service.createGroup(CTX, "dup", new GroupAttributes("Dup", null, 0, null, null)));
        verify(practiceGroupRepository, never()).save(any());
    }

    @Test
    void createGroup_appendsWhenOrderIsOmitted() {
        when(practiceGroupRepository.findMaxDisplayOrder(1L)).thenReturn(3);
        when(practiceGroupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PracticeGroup created =
                service.createGroup(CTX, "new-group", new GroupAttributes("New group", null, null, null, null));

        assertThat(created.getDisplayOrder()).isEqualTo(4);
    }

    @Test
    void updateGroup_appliesPartialChanges() {
        PracticeGroup group = new PracticeGroup();
        group.setId(7L);
        group.setSlug("g");
        group.setName("Old");
        group.setDescription("original blurb");
        group.setColor("slate");
        group.setDisplayOrder(0);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "g")).thenReturn(Optional.of(group));
        when(practiceGroupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PracticeGroup updated = service.updateGroup(CTX, "g", new GroupAttributes("New", null, 5, "Eye", null));

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getDisplayOrder()).isEqualTo(5);
        assertThat(updated.getIcon()).isEqualTo("Eye");
        assertThat(updated.getDescription()).isEqualTo("original blurb");
        assertThat(updated.getColor()).isEqualTo("slate");
    }

    @Test
    void updateGroup_recordsBeforeAndAfter() {
        PracticeGroup group = group("guidance");
        group.setId(7L);
        group.setName("Old");
        group.setVisibleInPracticeDashboards(true);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "guidance")).thenReturn(Optional.of(group));
        when(practiceGroupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateGroup(CTX, "guidance", new GroupAttributes("New", null, null, null, null), false);

        ConfigAuditEntry entry = capturedAuditEntry();
        assertThat(entry.entityType()).isEqualTo(ConfigAuditEntityType.PRACTICE_GROUP);
        assertThat(entry.before())
                .isEqualTo(new PracticeGroupSnapshot("guidance", "Old", null, true, null, null, null));
        assertThat(entry.after())
                .isEqualTo(new PracticeGroupSnapshot("guidance", "New", null, false, null, null, null));
    }

    @Test
    void updateGroup_appendsRevisionsWhenSnapshotAttributesChange() {
        PracticeGroup group = group("g");
        group.setId(7L);
        group.setName("Old");
        Practice first = new Practice();
        first.setId(1L);
        Practice second = new Practice();
        second.setId(2L);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "g")).thenReturn(Optional.of(group));
        when(practiceGroupRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(1L, 7L))
                .thenReturn(List.of(first, second));

        service.updateGroup(CTX, "g", new GroupAttributes("New", null, null, null, null));

        verify(practiceRevisionService).append(first);
        verify(practiceRevisionService).append(second);
    }

    @Test
    void changingInheritedAuthorityBumpsTheRolloutRevisionOnce() {
        PracticeGroup group = group("g");
        group.setId(7L);
        group.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        Practice first = practice("first");
        first.setId(1L);
        first.setGroup(group);
        Practice second = practice("second");
        second.setId(2L);
        second.setGroup(group);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "g")).thenReturn(Optional.of(group));
        when(practiceGroupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(1L, 7L))
                .thenReturn(List.of(first, second));

        service.setAutonomy(CTX, "g", PracticeAutonomy.AUTOMATIC);

        assertThat(workspace.getReviewSettings().getRolloutRevision()).isEqualTo(1);
    }

    @Test
    void changingAGroupDoesNotBumpWhenPracticesOverrideIt() {
        PracticeGroup group = group("g");
        group.setId(7L);
        group.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
        Practice practice = practice("p");
        practice.setId(1L);
        practice.setGroup(group);
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "g")).thenReturn(Optional.of(group));
        when(practiceGroupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(1L, 7L))
                .thenReturn(List.of(practice));

        service.setAutonomy(CTX, "g", PracticeAutonomy.OFF);

        assertThat(workspace.getReviewSettings().getRolloutRevision()).isZero();
    }

    @Test
    void getGroup_missing_throwsNotFound() {
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "nope")).thenReturn(Optional.empty());
        assertThatExceptionOfType(EntityNotFoundException.class).isThrownBy(() -> service.getGroup(CTX, "nope"));
    }

    @Test
    void bindPractice_appendsToTheDestinationGroup() {
        PracticeGroup destination = group("destination");
        destination.setId(20L);
        Practice moved = practice("moved");
        when(practiceRepository.findByWorkspaceIdAndSlug(1L, "moved")).thenReturn(Optional.of(moved));
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "destination"))
                .thenReturn(Optional.of(destination));
        when(practiceRepository.findMaxDisplayOrder(1L, 20L)).thenReturn(4);
        when(practiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Practice bound = service.bindPractice(CTX, "moved", "destination");

        assertThat(bound)
                .extracting(Practice::getGroup, Practice::getDisplayOrder)
                .containsExactly(destination, 5);
        verify(practiceRevisionService).append(bound);
    }

    @Test
    void bindPractice_nullGroupUnbinds() {
        Practice practice = practice("p");
        PracticeGroup currentGroup = new PracticeGroup();
        currentGroup.setId(10L);
        practice.setGroup(currentGroup);
        when(practiceRepository.findByWorkspaceIdAndSlug(1L, "p")).thenReturn(Optional.of(practice));
        when(practiceRepository.findMaxDisplayOrder(1L, null)).thenReturn(6);
        when(practiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Practice unbound = service.bindPractice(CTX, "p", null);

        assertThat(unbound)
                .extracting(Practice::getGroup, Practice::getDisplayOrder)
                .containsExactly(null, 7);
        verify(practiceRevisionService).append(unbound);
    }

    @Test
    void bindPractice_sameGroupDoesNotWrite() {
        PracticeGroup currentGroup = group("current");
        currentGroup.setId(10L);
        PracticeGroup resolvedGroup = group("current");
        resolvedGroup.setId(10L);
        Practice practice = practice("p");
        practice.setGroup(currentGroup);
        when(practiceRepository.findByWorkspaceIdAndSlug(1L, "p")).thenReturn(Optional.of(practice));
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "current")).thenReturn(Optional.of(resolvedGroup));

        assertThat(service.bindPractice(CTX, "p", "current")).isSameAs(practice);

        verify(practiceRepository, never()).findMaxDisplayOrder(any(), any());
        verify(practiceRepository, never()).save(any());
        verify(practiceRevisionService, never()).append(any());
    }

    @Test
    void bindPractice_unresolvedGroup_throwsNotFound() {
        when(practiceRepository.findByWorkspaceIdAndSlug(1L, "p")).thenReturn(Optional.of(practice("p")));
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "foreign")).thenReturn(Optional.empty());

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> service.bindPractice(CTX, "p", "foreign"));
        verify(practiceRepository, never()).save(any());
    }

    @Test
    void deleteGroup_appendsPracticesToUnassigned() {
        PracticeGroup group = group("deleted");
        group.setId(20L);
        Practice first = practice("first");
        first.setId(1L);
        first.setGroup(group);
        Practice second = practice("second");
        second.setId(2L);
        second.setGroup(group);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "deleted")).thenReturn(Optional.of(group));
        when(practiceRepository.findMaxDisplayOrder(1L, null)).thenReturn(3);
        when(practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(1L, 20L))
                .thenReturn(List.of(first, second));

        service.deleteGroup(CTX, "deleted");

        assertThat(List.of(first, second))
                .extracting(Practice::getGroup, Practice::getDisplayOrder)
                .containsExactly(tuple(null, 4), tuple(null, 5));
        verify(practiceRevisionService).append(first);
        verify(practiceRevisionService).append(second);
        verify(practiceGroupRepository).delete(group);
    }

    @Test
    void deleteGroup_deletesContainedPracticesWhenRequested() {
        PracticeGroup group = group("deleted");
        group.setId(20L);
        Practice first = practice("first");
        first.setId(1L);
        first.setGroup(group);
        Practice second = practice("second");
        second.setId(2L);
        second.setGroup(group);
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "deleted")).thenReturn(Optional.of(group));
        when(practiceRepository.findByWorkspaceIdAndGroupIdOrderByDisplayOrderAscNameAsc(1L, 20L))
                .thenReturn(List.of(first, second));

        service.deleteGroup(CTX, "deleted", true);

        verify(practiceRepository).delete(first);
        verify(practiceRepository).delete(second);
        verify(practiceRepository, never()).save(any());
        verify(practiceRevisionService, never()).append(any());
        verify(configAudit, times(3)).record(any());
        verify(practiceGroupRepository).delete(group);
    }

    @Test
    void deleteGroup_recordsTheDeletedConfiguration() {
        PracticeGroup group = group("deleted");
        group.setId(20L);
        group.setName("Deleted");
        when(practiceGroupRepository.findByWorkspaceIdAndSlug(1L, "deleted")).thenReturn(Optional.of(group));

        service.deleteGroup(CTX, "deleted");

        ConfigAuditEntry entry = capturedAuditEntry();
        assertThat(entry.entityType()).isEqualTo(ConfigAuditEntityType.PRACTICE_GROUP);
        assertThat(entry.before())
                .isEqualTo(new PracticeGroupSnapshot("deleted", "Deleted", null, true, null, null, null));
        assertThat(entry.after()).isNull();
    }

    private ConfigAuditEntry capturedAuditEntry() {
        ArgumentCaptor<ConfigAuditEntry> captor = ArgumentCaptor.forClass(ConfigAuditEntry.class);
        verify(configAudit).record(captor.capture());
        return captor.getValue();
    }

    private static PracticeGroup group(String slug) {
        PracticeGroup g = new PracticeGroup();
        g.setSlug(slug);
        return g;
    }

    private static Practice practice(String slug) {
        Practice practice = new Practice();
        practice.setSlug(slug);
        practice.setName(slug);
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setCriteria("criteria");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        return practice;
    }

    @Test
    void reorder_setsDisplayOrderByListIndex() {
        PracticeGroup a = group("a");
        PracticeGroup b = group("b");
        PracticeGroup c = group("c");
        when(practiceGroupRepository.findByWorkspaceIdOrderByDisplayOrderAscNameAsc(1L))
                .thenReturn(List.of(a, b, c));

        service.reorder(CTX, List.of("c", "a", "b"));

        assertThat(c.getDisplayOrder()).isEqualTo(0);
        assertThat(a.getDisplayOrder()).isEqualTo(1);
        assertThat(b.getDisplayOrder()).isEqualTo(2);
        verify(practiceGroupRepository, times(3)).save(any());
    }

    @Test
    void reorder_unknownSlug_throws() {
        when(practiceGroupRepository.findByWorkspaceIdOrderByDisplayOrderAscNameAsc(1L))
                .thenReturn(List.of(group("a"), group("b")));

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> service.reorder(CTX, List.of("ghost", "a", "b")));
        verify(practiceGroupRepository, never()).save(any());
    }

    @Test
    void reorder_duplicateSlug_throwsAndWritesNothing() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.reorder(CTX, List.of("a", "a")));
        verify(practiceGroupRepository, never()).save(any());
    }

    @Test
    void reorder_partialList_rejectsAndWritesNothing() {
        when(practiceGroupRepository.findByWorkspaceIdOrderByDisplayOrderAscNameAsc(1L))
                .thenReturn(List.of(group("a"), group("b"), group("c")));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.reorder(CTX, List.of("a", "b")));
        verify(practiceGroupRepository, never()).save(any());
    }
}
