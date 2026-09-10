package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Provider authority is independent of whether eligibility came from a request or a directory. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessEligibilityService {
    private final GitHubAccessDirectoryEvidence directory;
    private final GitHubAccessRequestEvidence requests;

    public void configure(GitHubAccessTarget target, Set<String> groups) {
        switch (target.getSource()) {
            case REQUEST -> requests.configure(target, groups);
            case DIRECTORY -> directory.configure(target, groups);
        }
    }

    public GitHubAccessEvidence.Eligibility read(GitHubAccessTarget target, boolean draft) {
        return switch (target.getSource()) {
            case REQUEST -> requests.read(target);
            case DIRECTORY -> directory.read(target, draft);
        };
    }

    public void lockSource(GitHubAccessTarget target) {
        if (target.getSource() == GitHubAccessTarget.Source.DIRECTORY) directory.lockSource(target);
    }

    public void bindScope(GitHubAccessTarget target, long organizationId, long scopeId) {
        if (target.getSource() == GitHubAccessTarget.Source.REQUEST)
            requests.bindScope(target, organizationId, scopeId);
    }

    public boolean sourceEnded(GitHubAccessTarget target) {
        return target.getSource() == GitHubAccessTarget.Source.DIRECTORY && directory.sourceEnded(target);
    }

    public boolean sameCapture(GitHubAccessEvidence.Eligibility first, GitHubAccessEvidence.Eligibility second) {
        return first.source() == second.source()
                && first.configurationVersion() == second.configurationVersion()
                && first.candidates().equals(second.candidates())
                && (first.source() == GitHubAccessTarget.Source.REQUEST
                        || (first.captureStartedAt().equals(second.captureStartedAt())
                                && Objects.equals(first.sourceVersion(), second.sourceVersion())
                                && first.groupIds().equals(second.groupIds())));
    }
}
