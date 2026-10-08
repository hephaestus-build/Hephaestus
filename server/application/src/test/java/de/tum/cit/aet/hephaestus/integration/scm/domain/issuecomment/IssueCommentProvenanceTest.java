package de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup.DeliveredComment;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IssueCommentProvenanceTest extends BaseUnitTest {
    private static final List<DeliveredComment> RECORDED = List.of(
            new DeliveredComment("IC_opaque", "https://github.com/org/repo/issues/7#issuecomment-600"),
            new DeliveredComment("gid://gitlab/Note/601", null),
            new DeliveredComment("602", null),
            new DeliveredComment("gid://gitlab/Note/601", "https://gitlab.example.com/org/repo/-/issues/7#note_601"));

    private static final List<DeliveredComment> UNUSABLE = List.of(
            new DeliveredComment("IC_opaque", null),
            new DeliveredComment("IC_opaque", "not a URI"),
            new DeliveredComment("gid://gitlab/Discussion/600", "https://example.com/#note_600"),
            new DeliveredComment("gid://gitlab/Note/not-an-id", "https://example.com/#issuecomment-quoted-marker"),
            new DeliveredComment(
                    "99999999999999999999999999", "https://example.com/#issuecomment-99999999999999999999999999"));

    private final DeliveredIssueCommentLookup deliveries = mock(DeliveredIssueCommentLookup.class);
    private final IssueCommentProvenance provenance = new IssueCommentProvenance(deliveries);

    @Test
    void shouldReadNativeIdsFromRecordedProviderIdentities() {
        when(deliveries.findForIssue(42L)).thenReturn(RECORDED);

        assertThat(provenance.deliveredIds(42L)).isEqualTo(Set.of(600L, 601L, 602L));
    }

    @Test
    void shouldKeepEvidenceWhenRecordedIdentitiesCannotIdentifyAMirroredComment() {
        when(deliveries.findForIssue(42L)).thenReturn(UNUSABLE);

        assertThat(provenance.deliveredIds(42L)).isEmpty();
    }

    @Test
    void shouldReadEachIssuesIdentitiesOnItsOwnWhenARepositoryIsReadAtOnce() {
        when(deliveries.findForRepository(5L, 9L)).thenReturn(Map.of(42L, RECORDED, 43L, UNUSABLE));

        assertThat(provenance.deliveredIdsByIssue(5L, 9L))
                .isEqualTo(Map.of(42L, Set.of(600L, 601L, 602L), 43L, Set.of()));
    }
}
