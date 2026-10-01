package de.tum.cit.aet.hephaestus.integration.core.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.IssueArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.PullRequestArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.github.manifest.GitHubManifest;
import java.util.List;
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

    @Override
    protected IntegrationManifest manifest() {
        return new GitHubManifest(true);
    }

    @Override
    protected List<ArtifactDescriptor> descriptors() {
        return List.of(new PullRequestArtifactDescriptor(), new IssueArtifactDescriptor());
    }
}
