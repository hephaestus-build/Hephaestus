package de.tum.cit.aet.hephaestus.core.auth.consent;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
     * The version of the wording on the first-login screen. That screen is in the webapp, published in
     * a signed release and immutable in git, so this is a pointer into that history rather than a row
     * anyone could edit afterwards. The webapp holds the same constant beside the words and refuses to
     * render the form when the two disagree; bump both in the commit that changes any of them.
     */
    static final String WORDING_VERSION = "2026-09-10";

    /** Enough of a digest to distinguish two organisation names, and short enough for the column. */
    private static final int ORGANISATION_DIGEST_LENGTH = 8;

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

    /**
     * What an account was actually shown: the wording, and the organisation the research question
     * named — because consent to a study is consent to the organisation running it, and renaming that
     * organisation asks a different question with the same words.
     *
     * <p>Recording the composite is what makes every downstream check follow the configuration for
     * free: turn a study on, off, or over to another organisation and completion lapses, so the
     * account is asked again instead of inheriting an answer it gave to somebody else.
     */
    String currentNoticeVersion() {
        String organisation = properties.researchProgramme();
        return organisation == null ? WORDING_VERSION : WORDING_VERSION + "+" + digestOf(organisation);
    }

    private static String digestOf(String organisation) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(organisation.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, ORGANISATION_DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }

    @Transactional(readOnly = true)
    public ConsentStatusDTO status(Long accountId) {
        return currentStatus(accountId);
    }

    private ConsentStatusDTO currentStatus(Long accountId) {
        return new ConsentStatusDTO(
                currentNoticeVersion(),
                WORDING_VERSION,
                isCurrentNoticeCompleted(accountId),
                researchAuthorised(accountId),
                properties.researchProgramme());
    }

    @Transactional(readOnly = true)
    public boolean hasCompletedCurrentNotice(Long accountId) {
        return isCurrentNoticeCompleted(accountId);
    }

    private boolean isCurrentNoticeCompleted(Long accountId) {
        return decisionRepository.isCompletedForNotice(
                accountId, currentNoticeVersion(), properties.researchProgramme() != null);
    }

    @Transactional
    public ConsentStatusDTO completeFirstLogin(Long accountId, FirstLoginConsentDTO request) {
        if (!currentNoticeVersion().equals(request.noticeVersion())) {
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
            appendResearchIfChanged(account, accountId, answer, ConsentDecision.Mechanism.FIRST_LOGIN_INTERSTITIAL);
        }
        decisionRepository.flush();
        return currentStatus(accountId);
    }

    @Transactional
    public ConsentStatusDTO setResearchParticipation(Long accountId, ResearchConsentDTO request) {
        if (properties.researchProgramme() == null) {
            // Participation is already false for every account while no study is configured, so there
            // is nothing here to grant and nothing left to withdraw.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This instance runs no research programme");
        }
        if (!isCurrentNoticeCompleted(accountId)) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "Complete the current transparency notice first");
        }
        Account account = requireAccountForUpdate(accountId);
        appendResearchIfChanged(account, accountId, request.granted(), ConsentDecision.Mechanism.ACCOUNT_SETTINGS);
        decisionRepository.flush();
        return currentStatus(accountId);
    }

    /**
     * Whether research processing is authorised right now. A grant given to a study that has since
     * been switched off, or handed to a different organisation, is history rather than permission:
     * both change the notice version, and only the current one authorises anything.
     */
    @Transactional(readOnly = true)
    public boolean participatesInResearch(Long accountId) {
        return researchAuthorised(accountId);
    }

    private boolean researchAuthorised(Long accountId) {
        if (properties.researchProgramme() == null) {
            return false;
        }
        ConsentDecision latest = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        return latest != null && latest.isGranted() && isForCurrentNotice(latest);
    }

    /**
     * Appends unless the answer on record already says this, for this notice. A resubmitted form
     * writes nothing; a changed answer, or one given against a notice that has since moved, is a
     * decision — including when its boolean happens to match, because the question was not the same.
     */
    private void appendResearchIfChanged(
            Account account, Long accountId, boolean granted, ConsentDecision.Mechanism mechanism) {
        ConsentDecision current = latest(accountId, ConsentDecision.Purpose.RESEARCH_PARTICIPATION);
        if (current == null || !isForCurrentNotice(current) || current.isGranted() != granted) {
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

    private boolean isForCurrentNotice(ConsentDecision decision) {
        return currentNoticeVersion().equals(decision.getNoticeVersion());
    }

    private @Nullable ConsentDecision latest(Long accountId, ConsentDecision.Purpose purpose) {
        return decisionRepository
                .findFirstByAccountIdAndPurposeOrderByOccurredAtDescIdDesc(accountId, purpose)
                .orElse(null);
    }

    private void append(
            Account account, ConsentDecision.Purpose purpose, boolean granted, ConsentDecision.Mechanism mechanism) {
        decisionRepository.save(new ConsentDecision(account, purpose, granted, mechanism, currentNoticeVersion()));
    }

    public record ConsentStatusDTO(
            @Schema(description = "Identifies the wording and the research organisation shown; echo it back to submit")
            @NonNull
            String noticeVersion,

            @Schema(description = "Version of the first-login wording the client must be rendering") @NonNull
            String wordingVersion,

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
