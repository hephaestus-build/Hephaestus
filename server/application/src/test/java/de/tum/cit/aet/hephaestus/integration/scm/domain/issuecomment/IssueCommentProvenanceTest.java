package de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup.DeliveredComment;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IssueCommentProvenanceTest extends BaseUnitTest {
    @Test
    void shouldReadNativeIdsFromRecordedProviderIdentities() {
        IssueCommentProvenance provenance = new IssueCommentProvenance(issueId -> {
            assertThat(issueId).isEqualTo(42L);
            return List.of(
                    new DeliveredComment("IC_opaque", "https://github.com/org/repo/issues/7#issuecomment-600"),
                    new DeliveredComment("gid://gitlab/Note/601", null),
                    new DeliveredComment("602", null),
                    new DeliveredComment(
                            "gid://gitlab/Note/601", "https://gitlab.example.com/org/repo/-/issues/7#note_601"));
        });

        assertThat(provenance.deliveredIds(42L)).isEqualTo(Set.of(600L, 601L, 602L));
    }

    @Test
    void shouldKeepEvidenceWhenRecordedIdentitiesCannotIdentifyAMirroredComment() {
        IssueCommentProvenance provenance = new IssueCommentProvenance(issueId -> List.of(
                new DeliveredComment("IC_opaque", null),
                new DeliveredComment("IC_opaque", "not a URI"),
                new DeliveredComment("gid://gitlab/Discussion/600", "https://example.com/#note_600"),
                new DeliveredComment("gid://gitlab/Note/not-an-id", "https://example.com/#issuecomment-quoted-marker"),
                new DeliveredComment(
                        "99999999999999999999999999", "https://example.com/#issuecomment-99999999999999999999999999")));

        assertThat(provenance.deliveredIds(42L)).isEmpty();
    }
}
