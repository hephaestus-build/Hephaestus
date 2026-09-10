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

    private static final String VERSION = ConsentService.CURRENT_NOTICE_VERSION;

    private final ConsentDecisionRepository decisionRepository = mock(ConsentDecisionRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);

    @BeforeEach
    void setUp() {
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(new Account("Ada")));
    }

    private ConsentService serviceWithResearch() {
        return new ConsentService(decisionRepository, accountRepository, new ConsentProperties("AET"));
    }

    private ConsentService serviceWithoutResearch() {
        return new ConsentService(decisionRepository, accountRepository, new ConsentProperties(" "));
    }

    private static ConsentDecision researchDecision(boolean granted, String noticeVersion) {
        return new ConsentDecision(
                new Account("Ada"),
                ConsentDecision.Purpose.RESEARCH_PARTICIPATION,
                granted,
                ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                noticeVersion);
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
        serviceWithResearch().completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, false));

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
                        .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO("obsolete", true, true)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(decisionRepository, never()).save(any());
        verify(accountRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void shouldRecordTheLaterAnswerWhenTwoSetupSubmissionsDisagree() {
        researchOnRecord(researchDecision(true, VERSION));

        serviceWithResearch().completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, false));

        assertThat(saved(3))
                .filteredOn(decision -> decision.getPurpose() == ConsentDecision.Purpose.RESEARCH_PARTICIPATION)
                .singleElement()
                .satisfies(decision -> assertThat(decision.isGranted()).isFalse());
    }

    @Test
    void shouldNotAppendAnythingWhenTheSameSetupSubmissionArrivesTwice() {
        researchOnRecord(researchDecision(true, VERSION));
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
                        42L, ConsentDecision.Purpose.TERMS_ACCEPTANCE))
                .thenReturn(Optional.of(new ConsentDecision(
                        new Account("Ada"),
                        ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                        true,
                        ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                        VERSION)));
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(
                        42L, ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT))
                .thenReturn(Optional.of(new ConsentDecision(
                        new Account("Ada"),
                        ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT,
                        true,
                        ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL,
                        VERSION)));

        serviceWithResearch().completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, true));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldSupersedeAnAnswerGivenAgainstAnOlderNotice() {
        researchOnRecord(researchDecision(true, "2026-08-30"));

        serviceWithResearch().completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, true));

        assertThat(saved(3).get(2))
                .satisfies(decision -> assertThat(decision.getNoticeVersion()).isEqualTo(VERSION));
    }

    @Test
    void shouldNotAskAboutResearchWhenNoOrganisationIsConfigured() {
        serviceWithoutResearch().completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, null));

        assertThat(saved(2))
                .extracting(ConsentDecision::getPurpose)
                .containsExactly(
                        ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                        ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT);
        verify(decisionRepository).isCompletedForNotice(42L, VERSION, false);
    }

    @Test
    void shouldRejectAResearchAnswerNobodyAskedFor() {
        assertThatThrownBy(() -> serviceWithoutResearch()
                        .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, false)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldRejectAMissingResearchAnswerWhenAnOrganisationIsConfigured() {
        assertThatThrownBy(() -> serviceWithResearch()
                        .completeFirstLogin(42L, new ConsentService.FirstLoginConsentDTO(VERSION, true, null)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldRefuseToChangeResearchParticipationWhenNoOrganisationIsConfigured() {
        assertThatThrownBy(() -> serviceWithoutResearch()
                        .setResearchParticipation(42L, new ConsentService.ResearchConsentDTO(true)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(decisionRepository, never()).save(any());
    }

    @Test
    void shouldReportTheConfiguredResearchOrganisation() {
        when(decisionRepository.isCompletedForNotice(eq(42L), eq(VERSION), eq(true)))
                .thenReturn(true);

        assertThat(serviceWithResearch().status(42L).researchOrganization()).isEqualTo("AET");
        assertThat(serviceWithoutResearch().status(42L).researchOrganization()).isNull();
    }
}
