package de.tum.cit.aet.hephaestus.core.auth.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private final ConsentDecisionRepository decisionRepository = mock(ConsentDecisionRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private ConsentService service;

    @BeforeEach
    void setUp() {
        service = new ConsentService(decisionRepository, accountRepository);
        when(decisionRepository.findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(accountRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(new Account("Ada")));
    }

    @Test
    void shouldAppendSeparateDecisionsWhenFirstLoginIsCompletedWithoutResearch() {
        service.completeFirstLogin(
                42L, new ConsentService.FirstLoginConsentDTO(ConsentService.CURRENT_NOTICE_VERSION, true, false));

        ArgumentCaptor<ConsentDecision> decisions = ArgumentCaptor.forClass(ConsentDecision.class);
        verify(decisionRepository, org.mockito.Mockito.times(3)).save(decisions.capture());
        assertThat(decisions.getAllValues())
                .extracting(ConsentDecision::getPurpose)
                .containsExactly(
                        ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                        ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT,
                        ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        assertThat(decisions.getAllValues().get(2).isGranted()).isFalse();
        assertThat(decisions.getAllValues()).allSatisfy(decision -> {
            assertThat(decision.getNoticeVersion()).isEqualTo(ConsentService.CURRENT_NOTICE_VERSION);
            assertThat(decision.getMechanism()).isEqualTo(ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL);
        });
    }

    @Test
    void shouldRejectStaleNoticeVersionWithoutWritingAnything() {
        assertThatThrownBy(() -> service.completeFirstLogin(
                        42L, new ConsentService.FirstLoginConsentDTO("obsolete", true, true)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(decisionRepository, never()).save(any());
        verify(accountRepository, never()).findByIdForUpdate(any());
    }
}
