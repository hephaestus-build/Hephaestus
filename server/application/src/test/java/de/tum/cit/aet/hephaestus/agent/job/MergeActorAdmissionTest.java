package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.manifest.GitHubManifest;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.manifest.GitLabManifest;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class MergeActorAdmissionTest extends BaseUnitTest {

    private static final IntegrationManifestRegistry MANIFESTS =
            new IntegrationManifestRegistry(List.of(new GitHubManifest(true), new GitLabManifest(true)));

    @Test
    void shouldWaitForTheMergerOfAGitLabMergeAPracticeJudges() {
        assertThat(MergeActorAdmission.awaitsMerger(
                        MANIFESTS,
                        merged(IdentityProviderType.GITLAB, null),
                        ScmSignals.PULL_REQUEST_MERGED,
                        List.of(merger())))
                .isTrue();
    }

    @Test
    void shouldNotWaitOnceTheMergerIsKnownOrNoPracticeJudgesThem() {
        assertThat(MergeActorAdmission.awaitsMerger(
                        MANIFESTS,
                        merged(IdentityProviderType.GITLAB, new User()),
                        ScmSignals.PULL_REQUEST_MERGED,
                        List.of(merger())))
                .isFalse();
        assertThat(MergeActorAdmission.awaitsMerger(
                        MANIFESTS,
                        merged(IdentityProviderType.GITLAB, null),
                        ScmSignals.PULL_REQUEST_MERGED,
                        List.of(new Practice())))
                .isFalse();
        assertThat(MergeActorAdmission.awaitsMerger(
                        MANIFESTS,
                        merged(IdentityProviderType.GITLAB, null),
                        ScmSignals.PULL_REQUEST_OPENED,
                        List.of(merger())))
                .isFalse();
    }

    @Test
    void shouldLeaveAGitHubMergeOnItsExistingPath() {
        assertThat(MergeActorAdmission.awaitsMerger(
                        MANIFESTS,
                        merged(IdentityProviderType.GITHUB, null),
                        ScmSignals.PULL_REQUEST_MERGED,
                        List.of(merger())))
                .isFalse();
    }

    private static PullRequest merged(IdentityProviderType type, @Nullable User mergedBy) {
        PullRequest pr = new PullRequest();
        pr.setProvider(new IdentityProvider(type, "https://example.test"));
        if (mergedBy != null) {
            pr.setMergedBy(mergedBy);
        }
        return pr;
    }

    private static Practice merger() {
        Practice practice = new Practice();
        practice.setSignals(List.of(ScmSignals.PULL_REQUEST_MERGED));
        practice.setEvidenceRequirements(PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.MERGER);
        practice.setPrecondition(null);
        return practice;
    }
}
