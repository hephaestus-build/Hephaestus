package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class MergeActorAdmissionTest extends BaseUnitTest {

    @Test
    void shouldWaitForTheMergerOfAGitLabMergeAPracticeJudges() {
        assertThat(MergeActorAdmission.awaitsMerger(
                        merged(IdentityProviderType.GITLAB, null), ScmSignals.PULL_REQUEST_MERGED, List.of(merger())))
                .isTrue();
    }

    @Test
    void shouldNotWaitOnceTheMergerIsKnownOrNoPracticeJudgesThem() {
        assertThat(MergeActorAdmission.awaitsMerger(
                        merged(IdentityProviderType.GITLAB, new User()),
                        ScmSignals.PULL_REQUEST_MERGED,
                        List.of(merger())))
                .isFalse();
        assertThat(MergeActorAdmission.awaitsMerger(
                        merged(IdentityProviderType.GITLAB, null),
                        ScmSignals.PULL_REQUEST_MERGED,
                        List.of(new Practice())))
                .isFalse();
        assertThat(MergeActorAdmission.awaitsMerger(
                        merged(IdentityProviderType.GITLAB, null), ScmSignals.PULL_REQUEST_OPENED, List.of(merger())))
                .isFalse();
    }

    @Test
    void shouldLeaveAGitHubMergeOnItsExistingPath() {
        assertThat(MergeActorAdmission.awaitsMerger(
                        merged(IdentityProviderType.GITHUB, null), ScmSignals.PULL_REQUEST_MERGED, List.of(merger())))
                .isFalse();
    }

    private static PullRequest merged(IdentityProviderType type, @org.jspecify.annotations.Nullable User mergedBy) {
        PullRequest pr = new PullRequest();
        pr.setProvider(new IdentityProvider(type, "https://example.test"));
        if (mergedBy != null) {
            pr.setMergedBy(mergedBy);
        }
        return pr;
    }

    private static Practice merger() {
        Practice practice = new Practice();
        practice.setBindings(List.of(new PracticeBinding(
                List.of(ScmSignals.PULL_REQUEST_MERGED),
                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                false,
                ActorRole.MERGER)));
        return practice;
    }
}
