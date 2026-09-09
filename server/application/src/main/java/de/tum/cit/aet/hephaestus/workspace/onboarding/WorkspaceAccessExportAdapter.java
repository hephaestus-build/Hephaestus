package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAccessRequestQuery;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class WorkspaceAccessExportAdapter implements AccountAccessRequestQuery {
    private final WorkspaceAccessRequestRepository requests;

    @Override
    @Transactional(readOnly = true)
    public List<Submission> submissionsForAccount(Long accountId) {
        return requests.findForAccountExport(accountId).stream()
                .map(request -> {
                    var submitted = request.getSubmission();
                    var policy = request.getPolicySnapshot();
                    var notices = policy.notices().stream()
                            .filter(notice -> submitted.acknowledgedNoticeKeys().contains(notice.key()))
                            .map(notice -> new Notice(notice.key(), notice.title(), notice.markdown()))
                            .toList();
                    return new Submission(
                            request.getWorkspace().getWorkspaceSlug(),
                            request.getSubmittedAt(),
                            submitted.details().expiresAt(),
                            submitted.details().teamIds(),
                            submitted.comments(),
                            policy.introductionMarkdown(),
                            policy.acknowledgementLabel(),
                            notices);
                })
                .toList();
    }
}
