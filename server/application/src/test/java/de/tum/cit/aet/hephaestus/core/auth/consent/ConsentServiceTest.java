package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ConsentServiceTest extends BaseUnitTest {

    private static final String VERSION = ConsentService.WORDING_VERSION;
    private static final String ORG = "AET";
    private static final ConsentProperties AET = new ConsentProperties(ORG);
    private static final ConsentProperties NO_STUDY = new ConsentProperties(" ");

    private final ConsentDecisionRepository decisionRepository = mock(ConsentDecisionRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);

    @BeforeEach
    void setUp() {
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(new Account("Ada")));
    }

    private ConsentService serviceWithResearch() {
        return new ConsentService(decisionRepository, accountRepository, AET);
    }

    private ConsentService serviceWithoutResearch() {
        return new ConsentService(decisionRepository, accountRepository, NO_STUDY);
    }

    private static ConsentDecision researchDecision(boolean granted, String noticeVersion, String organisation) {
        return new ConsentDecision(
                new Account("Ada"),
                ConsentDecision.Purpose.RESEARCH_PARTICIPATION,
                granted,
                ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                noticeVersion,
                organisation);
    }

    private void researchOnRecord(ConsentDecision decision) {
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
                        42L, ConsentDecision.Purpose.RESEARCH_PARTICIPATION))
                .thenReturn(Optional.of(decision));
    }

    private java.util.List<ConsentDecision> saved(int times) {
        ArgumentCaptor<ConsentDecision> decisions = ArgumentCaptor.forClass(ConsentDecision.class);
        verify(decisionRepository, times(times)).save(decisions.capture());
        return decisions.getAllValues();
    }

    @Test
    void shouldAppendSeparateDecisionsWhenFirstLoginIsCompletedWithoutResearch() {
        serviceWithResearch()
                .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, false, ORG));

        assertThat(saved(3))
                .extracting(ConsentDecision::getPurpose)
                .containsExactly(
                        ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                        ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT,
                        ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        assertThat(saved(3).get(2).isGranted()).isFalse();
        assertThat(saved(3)).allSatisfy(decision -> {
            assertThat(decision.getNoticeVersion()).isEqualTo(VERSION);
            assertThat(decision.getMechanism()).isEqualTo(ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL);
        });
    }

    @Test
    void shouldRejectStaleNoticeVersionWithoutWritingAnything() {
        assertThatThrownBy(() -> serviceWithResearch()
                        .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO("obsolete", true, true, ORG)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(decisionRepository, never()).save(any());
        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void shouldRecordTheLaterAnswerWhenTwoSetupSubmissionsDisagree() {
        researchOnRecord(researchDecision(true, VERSION, ORG));

        serviceWithResearch()
                .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, false, ORG));

        assertThat(saved(3))
                .filteredOn(decision -> decision.getPurpose() == ConsentDecision.Purpose.RESEARCH_PARTICIPATION)
                .singleElement()
                .satisfies(decision -> assertThat(decision.isGranted()).isFalse());
    }

    @Test
    void shouldNotAppendAnythingWhenTheSameSetupSubmissionArrivesTwice() {
        researchOnRecord(researchDecision(true, VERSION, ORG));
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
                        42L, ConsentDecision.Purpose.TERMS_ACCEPTANCE))
                .thenReturn(Optional.of(new ConsentDecision(
                        new Account("Ada"),
                        ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                        true,
                        ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                        VERSION,
                        null)));
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
                        42L, ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT))
                .thenReturn(Optional.of(new ConsentDecision(
                        new Account("Ada"),
                        ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT,
                        true,
                        ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                        VERSION,
                        null)));

        serviceWithResearch()
                .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, true, ORG));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldSupersedeAnAnswerGivenAgainstAnOlderNotice() {
        researchOnRecord(researchDecision(true, "2026-08-30", ORG));

        serviceWithResearch()
                .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, true, ORG));

        assertThat(saved(3).get(2))
                .satisfies(decision -> assertThat(decision.getNoticeVersion()).isEqualTo(VERSION));
    }

    @Test
    void shouldNotAskAboutResearchWhenNoOrganisationIsConfigured() {
        serviceWithoutResearch()
                .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, null, null));

        assertThat(saved(2))
                .extracting(ConsentDecision::getPurpose)
                .containsExactly(
                        ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                        ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT);
        verify(decisionRepository).hasAcceptedNotice(42L, VERSION);
    }

    @Test
    void shouldRejectAResearchAnswerNobodyAskedFor() {
        assertThatThrownBy(() -> serviceWithoutResearch()
                        .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, false, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldRejectAMissingResearchAnswerWhenAnOrganisationIsConfigured() {
        assertThatThrownBy(() -> serviceWithResearch()
                        .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, null, ORG)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldRefuseToChangeResearchParticipationWhenNoOrganisationIsConfigured() {
        assertThatThrownBy(() -> serviceWithoutResearch()
                        .setResearchParticipation(42L, new ConsentService.ResearchConsentDTO(VERSION, true, ORG)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldArchiveTheOrganisationTheQuestionNamed() {
        serviceWithResearch()
                .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, true, ORG));

        assertThat(saved(3))
                .filteredOn(decision -> decision.getPurpose() == ConsentDecision.Purpose.RESEARCH_PARTICIPATION)
                .singleElement()
                .satisfies(decision ->
                        assertThat(decision.getResearchOrganization()).isEqualTo(ORG));
        assertThat(saved(3))
                .filteredOn(decision -> decision.getPurpose() != ConsentDecision.Purpose.RESEARCH_PARTICIPATION)
                .allSatisfy(decision ->
                        assertThat(decision.getResearchOrganization()).isNull());
    }

    @Test
    void shouldRejectASubmissionNamingAnOrganisationThisInstanceNoLongerRuns() {
        assertThatThrownBy(() -> serviceWithResearch()
                        .completeFirstLogin(
                                42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, true, "Another lab")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldRejectASettingsSubmissionNamingASupersededOrganisation() {
        assertThatThrownBy(() -> serviceWithResearch()
                        .setResearchParticipation(
                                42L, new ConsentService.ResearchConsentDTO(VERSION, true, "Another lab")))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldNotCarryAGrantAcrossAChangeOfResearchOrganisation() {
        researchOnRecord(researchDecision(true, VERSION, ORG));
        ConsentService other =
                new ConsentService(decisionRepository, accountRepository, new ConsentProperties("Another lab"));

        assertThat(other.participates(42L)).isFalse();
    }

    @Test
    void shouldReportNoParticipationWhileNoStudyIsConfigured() {
        researchOnRecord(researchDecision(true, VERSION, ORG));

        assertThat(serviceWithoutResearch().participates(42L)).isFalse();
        assertThat(serviceWithoutResearch().status(42L).participateInResearch()).isFalse();
    }

    /**
     * An answer that named another organisation leaves the question open, so settings sends the
     * account back through setup rather than letting a switch stand in for a consent it never gave.
     */
    @Test
    void shouldSendTheAccountBackToSetupWhenTheAnswerOnRecordNamedAnotherOrganisation() {
        when(decisionRepository.hasAcceptedNotice(eq(42L), eq(VERSION))).thenReturn(true);
        researchOnRecord(researchDecision(true, VERSION, "Another lab"));

        assertThatThrownBy(() -> serviceWithResearch()
                        .setResearchParticipation(42L, new ConsentService.ResearchConsentDTO(VERSION, true, ORG)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldRecordAWithdrawalFromSettings() {
        when(decisionRepository.hasAcceptedNotice(eq(42L), eq(VERSION))).thenReturn(true);
        researchOnRecord(researchDecision(true, VERSION, ORG));

        serviceWithResearch().setResearchParticipation(42L, new ConsentService.ResearchConsentDTO(VERSION, false, ORG));

        assertThat(saved(1)).singleElement().satisfies(decision -> {
            assertThat(decision.isGranted()).isFalse();
            assertThat(decision.getResearchOrganization()).isEqualTo(ORG);
            assertThat(decision.getMechanism()).isEqualTo(ConsentDecision.Mechanism.ACCOUNT_SETTINGS);
        });
    }

    /** A → B → A: the A row is superseded by the B one, so the question is open again. */
    @Test
    void shouldNotTreatASupersededAnswerAsCompletionWhenTheOrganisationReturns() {
        when(decisionRepository.hasAcceptedNotice(eq(42L), eq(VERSION))).thenReturn(true);
        researchOnRecord(researchDecision(false, VERSION, "Another lab"));

        assertThat(serviceWithResearch().status(42L).completed()).isFalse();
        assertThat(serviceWithResearch().participates(42L)).isFalse();
    }

    @Test
    void shouldTreatARefusalAsAnAnswer() {
        when(decisionRepository.hasAcceptedNotice(eq(42L), eq(VERSION))).thenReturn(true);
        researchOnRecord(researchDecision(false, VERSION, ORG));

        assertThat(serviceWithResearch().status(42L).completed()).isTrue();
        assertThat(serviceWithResearch().participates(42L)).isFalse();
    }

    @Test
    void shouldReportTheConfiguredResearchOrganisation() {
        when(decisionRepository.hasAcceptedNotice(eq(42L), eq(VERSION))).thenReturn(true);

        assertThat(serviceWithResearch().status(42L).researchOrganization()).isEqualTo("AET");
        assertThat(serviceWithoutResearch().status(42L).researchOrganization()).isNull();
    }
}
