package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

class PracticeRevisionServiceTest extends BaseUnitTest {

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private PracticeRevisionRepository revisionRepository;

    @InjectMocks
    private PracticeRevisionService service;

    private Practice practice;

    @BeforeEach
    void setUp() {
        practice = new Practice();
        practice.setId(42L);
        practice.setSlug("clear-feedback");
        practice.setName("Clear feedback");
        PracticeTestEvidence.configure(practice, ArtifactKinds.PULL_REQUEST);
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice.setCriteria("Give specific feedback");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        when(practiceRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(practice));
        when(revisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldAppendANewStandardRevisionOnceWithoutRewritingHistoricalProvenance() {
        PracticeRevision historical = org.mockito.Mockito.mock(PracticeRevision.class);
        when(historical.getReviewRuleFingerprint()).thenReturn("v4:" + "a".repeat(64));
        when(historical.getRevisionNumber()).thenReturn(4);
        practice.setCurrentRevision(historical);
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.of(historical));

        PracticeRevision current = service.forReview(practice);
        assertThat(current.getRevisionNumber()).isEqualTo(5);
        assertThat(current.getReviewRuleFingerprint()).startsWith("v5:");
        assertThat(historical.getReviewRuleFingerprint()).startsWith("v4:");
        assertThat(service.forReview(practice)).isSameAs(current);
        org.mockito.Mockito.verify(revisionRepository).save(current);
    }

    @Test
    void shouldGiveEachNamedPracticeTheRevisionAReviewWouldPinWhenAskedForAWorkspace() {
        PracticeRevision historical = org.mockito.Mockito.mock(PracticeRevision.class);
        when(historical.getReviewRuleFingerprint()).thenReturn("v4:" + "a".repeat(64));
        when(historical.getRevisionNumber()).thenReturn(4);
        practice.setCurrentRevision(historical);
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.of(historical));
        when(practiceRepository.findByWorkspaceIdAndSlugIn(7L, List.of("clear-feedback")))
                .thenReturn(List.of(practice));

        List<PracticeRevision> pinned = service.forReview(7L, List.of("clear-feedback"));

        assertThat(pinned).singleElement().satisfies(revision -> {
            assertThat(revision.getRevisionNumber()).isEqualTo(5);
            assertThat(revision.getReviewRuleFingerprint()).startsWith("v5:");
            assertThat(revision.getCriteria()).isEqualTo("Give specific feedback");
        });
    }

    @Test
    void numbersEachRevisionAfterTheLast() {
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.of(new PracticeRevision(practice, 4)));

        assertThat(service.append(practice).getRevisionNumber()).isEqualTo(5);
    }

    @Test
    void startsAtOneForAPracticeWithNoHistory() {
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.empty());

        assertThat(service.append(practice).getRevisionNumber()).isOne();
    }

    @Test
    void capturesTheDefinitionAsItWasSoAFindingCanCiteIt() {
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.empty());

        PracticeRevision appended = service.append(practice);

        assertThat(appended.getCriteria()).isEqualTo("Give specific feedback");
        assertThat(appended.getReviewRuleFingerprint()).hasSize(67).startsWith("v5:");
        assertThat(practice.getCurrentRevision()).isSameAs(appended);
    }

    @Test
    void editingOnlyWhatPeopleReadLeavesTheReviewRuleFingerprintAlone() {
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.empty());
        String before = service.append(practice).getReviewRuleFingerprint();

        practice.setWhyItMatters("It shortens review cycles.");

        assertThat(service.append(practice).getReviewRuleFingerprint()).isEqualTo(before);
    }

    @Test
    void editingTheDetectionCriteriaChangesTheFingerprint() {
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.empty());
        String before = service.append(practice).getReviewRuleFingerprint();

        practice.setCriteria("Changed detector criteria");

        assertThat(service.append(practice).getReviewRuleFingerprint()).isNotEqualTo(before);
    }

    @Test
    void editingTheEvidenceDeclarationChangesTheFingerprint() {
        when(revisionRepository.findFirstByPracticeIdOrderByRevisionNumberDesc(42L))
                .thenReturn(Optional.empty());
        String before = service.append(practice).getReviewRuleFingerprint();

        // The fingerprint has to follow an evidence edit: a review that reads a different set of
        // sources is a different rule, and a stale digest would report it as the shipped one.
        practice.setSignals(List.of(ScmSignals.PULL_REQUEST_OPENED));
        practice.setEvidenceRequirements(List.of(
                new PracticeEvidenceRequirement(new SourceKind("scm.pull-request.core"), EvidenceStance.REQUIRED),
                new PracticeEvidenceRequirement(new SourceKind("scm.review-threads"), EvidenceStance.CONTEXTUAL)));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);

        assertThat(service.append(practice).getReviewRuleFingerprint()).isNotEqualTo(before);
    }
}
