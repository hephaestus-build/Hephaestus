package de.tum.cit.aet.hephaestus.integration.core.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.FileItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestReviewItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.RepositoryItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.github.manifest.GitHubManifest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** GitHub through the shared acceptance suite. */
class GitHubManifestContractTest extends IntegrationManifestContractTest {

    @Test
    void shouldInterpretGitHubsNativeReviewCommitWithoutInventingAMissingOne() {
        var github = manifest();
        var head = "a".repeat(40);
        assertThat(github.reviewCommitFor(head, head)).isEqualTo("CURRENT_HEAD");
        assertThat(github.reviewCommitFor("b".repeat(40), head)).isEqualTo("OTHER_COMMIT");
        assertThat(github.reviewCommitFor(null, head)).isEqualTo("UNKNOWN");
        assertThat(github.reviewCommitFor(head, null)).isEqualTo("UNKNOWN");
        assertThat(github.reviewCommitFor("", head)).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldDeclareSubmittedReviewEventsAndDeliveryOnTheirOwnItemKind() {
        var contribution = manifest().reviewContribution();
        assertThat(contribution.observes())
                .contains(ScmItemEvents.PULL_REQUEST_REVIEW, ScmItemEvents.REPOSITORY, ScmItemEvents.FILE);
        assertThat(contribution.raises())
                .containsEntry(ScmItemEvents.PULL_REQUEST_REVIEW, Set.of(ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED));
        assertThat(contribution.raises().get(ScmItemEvents.PULL_REQUEST))
                .doesNotContain(ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED);
        assertThat(contribution.delivers())
                .containsEntry(
                        ScmItemEvents.PULL_REQUEST_REVIEW, Set.of(CopyCapability.SUMMARY, CopyCapability.LINE_NOTE));
    }

    @Override
    protected IntegrationManifest manifest() {
        return new GitHubManifest();
    }

    @Override
    protected List<ItemDescriptor> descriptors() {
        return List.of(
                new PullRequestItemDescriptor(),
                new IssueItemDescriptor(),
                new PullRequestReviewItemDescriptor(),
                new RepositoryItemDescriptor(),
                new FileItemDescriptor());
    }
}
