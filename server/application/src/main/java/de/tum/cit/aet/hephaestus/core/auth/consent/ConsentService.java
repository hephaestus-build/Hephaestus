package de.tum.cit.aet.hephaestus.core.auth.consent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Consent decisions belong to an account, not a workspace")
public class ConsentService {

    /**
     * Identifies the wording an account was shown. The wording itself is the first-login screen in the
     * webapp, published in a signed release and immutable in git, so this version is a pointer into
     * that history rather than a row anyone could edit afterwards.
     *
     * <p>The webapp holds the same constant beside the words themselves and submits its own, so a tab
     * that predates a deployment cannot record acceptance of wording it never rendered — it gets the
     * conflict below instead. Bump both in the commit that changes any of those words.
     */
    static final String CURRENT_NOTICE_VERSION = "2026-09-10";

    private final ConsentDecisionRepository decisionRepository;
    private final AccountRepository accountRepository;
    private final ConsentProperties properties;

    public ConsentService(
            ConsentDecisionRepository decisionRepository,
            AccountRepository accountRepository,
            ConsentProperties properties) {
        this.decisionRepository = decisionRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ConsentStatusDTO status(Long accountId) {
        return currentStatus(accountId);
    }

    private ConsentStatusDTO currentStatus(Long accountId) {
        ConsentDecision research = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        return new ConsentStatusDTO(
                CURRENT_NOTICE_VERSION,
                isCurrentNoticeCompleted(accountId),
                research != null && research.isGranted() && CURRENT_NOTICE_VERSION.equals(research.getNoticeVersion()),
                properties.researchProgramme());
    }

    @Transactional(readOnly = true)
    public boolean hasCompletedCurrentNotice(Long accountId) {
        return isCurrentNoticeCompleted(accountId);
    }

    private boolean isCurrentNoticeCompleted(Long accountId) {
        return decisionRepository.isCompletedForNotice(
                accountId, CURRENT_NOTICE_VERSION, properties.researchProgramme() != null);
    }

    @Transactional
    public ConsentStatusDTO completeFirstLogin(Long accountId, FirstLoginConsentDTO request) {
        if (!CURRENT_NOTICE_VERSION.equals(request.noticeVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The transparency notice has changed; review it again");
        }
        if (!request.termsAccepted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terms must be accepted to use Hephaestus");
        }
        boolean asksAboutResearch = properties.researchProgramme() != null;
        if (asksAboutResearch && request.participateInResearch() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer the research question to continue");
        }
        if (!asksAboutResearch && request.participateInResearch() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This instance runs no research programme");
        }
        Account account = requireAccountForUpdate(accountId);
        if (!hasCurrentGrant(accountId, ConsentDecision.Purpose.TERMS_ACCEPTANCE)) {
            append(
                    account,
                    ConsentDecision.Purpose.TERMS_ACCEPTANCE,
                    true,
                    ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL);
        }
        if (!hasCurrentGrant(accountId, ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT)) {
            append(
                    account,
                    ConsentDecision.Purpose.PRIVACY_NOTICE_ACKNOWLEDGEMENT,
                    true,
                    ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL);
        }
        Boolean answer = request.participateInResearch();
        if (answer != null) {
            appendResearchIfChanged(
                    account, accountId, answer, ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL, true);
        }
        decisionRepository.flush();
        return currentStatus(accountId);
    }

    @Transactional
    public ConsentStatusDTO setResearchParticipation(Long accountId, ResearchConsentDTO request) {
        if (properties.researchProgramme() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This instance runs no research programme");
        }
        if (!isCurrentNoticeCompleted(accountId)) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "Complete the current transparency notice first");
        }
        Account account = requireAccountForUpdate(accountId);
        appendResearchIfChanged(
                account, accountId, request.granted(), ConsentDecision.Mechanism.ACCOUNT_SETTINGS, false);
        decisionRepository.flush();
        return currentStatus(accountId);
    }

    @Transactional(readOnly = true)
    public boolean participatesInResearch(Long accountId) {
        ConsentDecision latest = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        return latest != null && latest.isGranted() && CURRENT_NOTICE_VERSION.equals(latest.getNoticeVersion());
    }

    /**
     * Appends only when the answer differs from the one currently on record, which makes a repeated
     * submission a no-op and a changed one a decision. Setup takes {@code onlyIfNotForCurrentNotice}
     * because an account arriving from an older notice has an answer that predates the wording it is
     * being asked about: that one is superseded rather than compared.
     */
    private void appendResearchIfChanged(
            Account account,
            Long accountId,
            boolean granted,
            ConsentDecision.Mechanism mechanism,
            boolean supersedeOlderNotice) {
        ConsentDecision current = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        boolean stale = supersedeOlderNotice && (current == null || !isForCurrentNotice(current));
        if (stale || current == null || current.isGranted() != granted) {
            append(account, ConsentDecision.Purpose.RESEARCH_PARTICIPATION, granted, mechanism);
        }
    }

    private Account requireAccountForUpdate(Long accountId) {
        return accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
    }

    private boolean hasCurrentGrant(Long accountId, ConsentDecision.Purpose purpose) {
        ConsentDecision decision = latest(accountId, purpose);
        return decision != null && decision.isGranted() && isForCurrentNotice(decision);
    }

    private static boolean isForCurrentNotice(ConsentDecision decision) {
        return CURRENT_NOTICE_VERSION.equals(decision.getNoticeVersion());
    }

    private @Nullable ConsentDecision latest(Long accountId, ConsentDecision.Purpose purpose) {
        return decisionRepository
                .findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(accountId, purpose)
                .orElse(null);
    }

    private void append(
            Account account, ConsentDecision.Purpose purpose, boolean granted, ConsentDecision.Mechanism mechanism) {
        decisionRepository.save(new ConsentDecision(account, purpose, granted, mechanism, CURRENT_NOTICE_VERSION));
    }

    public record ConsentStatusDTO(
            @NonNull String noticeVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean completed,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean participateInResearch,

            @Schema(
                    description =
                            "Organisation running the optional research programme, or null when this instance runs none")
            @Nullable
            String researchOrganization) {}

    public record FirstLoginConsentDTO(
            @NonNull String noticeVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean termsAccepted,

            @Schema(description = "Required when the instance names a research organisation, omitted otherwise")
            @Nullable
            Boolean participateInResearch) {
        public FirstLoginConsentDTO {
            Objects.requireNonNull(noticeVersion, "noticeVersion");
        }
    }

    public record ResearchConsentDTO(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean granted) {}
}
