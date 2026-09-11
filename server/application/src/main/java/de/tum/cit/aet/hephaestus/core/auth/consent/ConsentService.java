package de.tum.cit.aet.hephaestus.core.auth.consent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Consent decisions belong to an account, not a workspace")
public class ConsentService implements ResearchParticipationQuery {

    /**
     * The version of the wording on the first-login screen. That screen is in the webapp, published in
     * a signed release and immutable in git, so this is a pointer into that history rather than a row
     * anyone could edit afterwards. The webapp holds the same constant beside the words and refuses to
     * render the form when the two disagree; bump both in the commit that changes any of them.
     */
    static final String WORDING_VERSION = "2026-09-11";

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
        return new ConsentStatusDTO(
                WORDING_VERSION,
                isCurrentNoticeCompleted(accountId),
                researchAuthorised(accountId),
                properties.researchProgramme());
    }

    @Transactional(readOnly = true)
    public boolean hasCompletedCurrentNotice(Long accountId) {
        return isCurrentNoticeCompleted(accountId);
    }

    /**
     * Setup is done when the terms are accepted for this wording and, where a study is configured, the
     * research question has an answer — granted or refused — for the organisation currently named.
     *
     * <p>The research half reads the same latest decision as {@link #researchAuthorised(Long)} rather
     * than asking whether such a row exists anywhere: an account that answered A, then B, then found
     * itself back on A would otherwise be let through on a superseded row while participation, which
     * reads the latest, said the opposite.
     */
    private boolean isCurrentNoticeCompleted(Long accountId) {
        if (!decisionRepository.hasAcceptedNotice(accountId, WORDING_VERSION)) {
            return false;
        }
        if (properties.researchProgramme() == null) {
            return true;
        }
        ConsentDecision latest = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        return latest != null && isForCurrentQuestion(latest);
    }

    @Transactional
    public ConsentStatusDTO completeFirstLogin(Long accountId, FirstLoginConsentDTO request) {
        requireCurrentNotice(request.noticeVersion(), request.researchOrganization());
        if (!request.termsAccepted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terms must be accepted to use Hephaestus");
        }
        String organisation = properties.researchProgramme();
        if (organisation != null && request.participateInResearch() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Answer the research question to continue");
        }
        if (organisation == null && request.participateInResearch() != null) {
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
        if (answer != null && organisation != null) {
            appendResearchIfChanged(
                    account, accountId, answer, organisation, ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL);
        }
        decisionRepository.flush();
        return currentStatus(accountId);
    }

    @Transactional
    public ConsentStatusDTO setResearchParticipation(Long accountId, ResearchConsentDTO request) {
        String organisation = properties.researchProgramme();
        if (organisation == null) {
            // Participation is already false for every account while no study is configured, so there
            // is nothing here to grant and nothing left to withdraw.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This instance runs no research programme");
        }
        requireCurrentNotice(request.noticeVersion(), request.researchOrganization());
        if (!isCurrentNoticeCompleted(accountId)) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "Complete the current transparency notice first");
        }
        Account account = requireAccountForUpdate(accountId);
        appendResearchIfChanged(
                account, accountId, request.granted(), organisation, ConsentDecision.Mechanism.ACCOUNT_SETTINGS);
        decisionRepository.flush();
        return currentStatus(accountId);
    }

    @Override
    public Optional<String> researchOrganization() {
        return Optional.ofNullable(properties.researchProgramme());
    }

    /**
     * Whether research processing is authorised right now. A grant given to a study that has since
     * been switched off, or handed to a different organisation, is history rather than permission:
     * both change the notice version, and only the current one authorises anything.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean participates(long accountId) {
        return researchAuthorised(accountId);
    }

    private boolean researchAuthorised(Long accountId) {
        if (properties.researchProgramme() == null) {
            return false;
        }
        ConsentDecision latest = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        return latest != null && latest.isGranted() && isForCurrentQuestion(latest);
    }

    /**
     * Appends unless the answer on record already says this, for this notice. A resubmitted form
     * writes nothing; a changed answer, or one given against a notice that has since moved, is a
     * decision — including when its boolean happens to match, because the question was not the same.
     */
    private void appendResearchIfChanged(
            Account account,
            Long accountId,
            boolean granted,
            String organisation,
            ConsentDecision.Mechanism mechanism) {
        ConsentDecision current = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        if (current == null || !isForCurrentQuestion(current) || current.isGranted() != granted) {
            decisionRepository.save(new ConsentDecision(
                    account,
                    ConsentDecision.Purpose.RESEARCH_PARTICIPATION,
                    granted,
                    mechanism,
                    WORDING_VERSION,
                    organisation));
        }
    }

    /** Rejects a submission that answers a question this instance is no longer putting. */
    private void requireCurrentNotice(String noticeVersion, @Nullable String researchOrganization) {
        if (!WORDING_VERSION.equals(noticeVersion)
                || !Objects.equals(properties.researchProgramme(), researchOrganization)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The transparency notice has changed; review it again");
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

    private boolean isForCurrentNotice(ConsentDecision decision) {
        return WORDING_VERSION.equals(decision.getNoticeVersion());
    }

    /** The same wording, and the same organisation named — a rename asks a different question. */
    private boolean isForCurrentQuestion(ConsentDecision decision) {
        return isForCurrentNotice(decision)
                && Objects.equals(properties.researchProgramme(), decision.getResearchOrganization());
    }

    private @Nullable ConsentDecision latest(Long accountId, ConsentDecision.Purpose purpose) {
        return decisionRepository
                .findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(accountId, purpose)
                .orElse(null);
    }

    private void append(
            Account account, ConsentDecision.Purpose purpose, boolean granted, ConsentDecision.Mechanism mechanism) {
        decisionRepository.save(new ConsentDecision(account, purpose, granted, mechanism, WORDING_VERSION, null));
    }

    public record ConsentStatusDTO(
            @Schema(description = "Version of the first-login wording the client must be rendering") @NonNull
            String noticeVersion,

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
            Boolean participateInResearch,

            @Schema(description = "The organisation the research question named on screen; omitted when it asked none")
            @Nullable
            String researchOrganization) {
        public FirstLoginConsentDTO {
            Objects.requireNonNull(noticeVersion, "noticeVersion");
        }
    }

    public record ResearchConsentDTO(
            @NonNull String noticeVersion,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean granted,

            @Schema(description = "The organisation this control named on screen") @Nullable
            String researchOrganization) {
        public ResearchConsentDTO {
            Objects.requireNonNull(noticeVersion, "noticeVersion");
        }
    }
}
