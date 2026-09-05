package de.tum.cit.aet.hephaestus.integration.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The grammar is the contract that keeps persisted vocabulary readable years after the code that
 * wrote it, so these tests are about what the parser must <em>refuse</em> as much as what it accepts.
 */
class SignalGrammarTest extends BaseUnitTest {

    @Nested
    class Kinds {

        @Test
        void shouldAcceptATwoSegmentLowercaseKind() {
            assertThat(ArtifactKind.of("scm.pull_request").value()).isEqualTo("scm.pull_request");
        }

        @Test
        void shouldRejectAColonWhichWouldReScopeCooldownKeys() {
            // Agent-job idempotency keys are colon-delimited and split on the LAST colon; a kind
            // carrying one would silently change which subject a cooldown covers.
            assertThatThrownBy(() -> ArtifactKind.of("scm:pull_request")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectAVendorPrefixedThreeSegmentKind() {
            assertThatThrownBy(() -> ArtifactKind.of("github.scm.pull_request"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectUppercase() {
            assertThatThrownBy(() -> ArtifactKind.of("scm.pullRequest")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Names {

        @Test
        void shouldDeriveTheArtifactKindFromTheNamePrefix() {
            assertThat(SignalName.of("scm.pull_request.merged").artifactKind())
                    .isEqualTo(ArtifactKind.of("scm.pull_request"));
        }

        @Test
        void shouldRejectANameThatIsOnlyAKind() {
            assertThatThrownBy(() -> SignalName.of("scm.pull_request")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectAColon() {
            assertThatThrownBy(() -> SignalName.of("scm.pull_request:merged"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Revisions {

        @Test
        void shouldKeepDescriptionEditsDistinguishableAtAnUnchangedHeadCommit() {
            // The finding the ledger is built around: a practice whose criteria are about the
            // description must stay re-measurable after the author fixes it, and no commit moved.
            SignalRevision before = SignalRevision.ofContentDigest("Add caching", "It is faster.");
            SignalRevision after = SignalRevision.ofContentDigest("Add caching", "Cuts p99 by 40%.");

            assertThat(after).isNotEqualTo(before);
        }

        @Test
        void shouldProduceTheSameDigestForTheSameContent() {
            assertThat(SignalRevision.ofContentDigest("title", "body"))
                    .isEqualTo(SignalRevision.ofContentDigest("title", "body"));
        }

        @Test
        void shouldSeparatePartsSoAdjacentFieldsCannotImpersonateEachOther() {
            assertThat(SignalRevision.ofContentDigest("ab", "c"))
                    .isNotEqualTo(SignalRevision.ofContentDigest("a", "bc"));
        }

        @Test
        void shouldTreatAnEmptyBodyAsDifferentFromAnAbsentOne() {
            assertThat(SignalRevision.ofContentDigest("title", ""))
                    .isNotEqualTo(SignalRevision.ofContentDigest("title", (String) null));
        }

        @Test
        void shouldNeverCollideAcrossSchemes() {
            String sameSubject = "MERGED";

            assertThat(SignalRevision.ofTerminalState(sameSubject))
                    .isNotEqualTo(SignalRevision.ofHeadCommit(sameSubject));
        }

        @Test
        void shouldReportTheSchemeThatProducedIt() {
            assertThat(SignalRevision.ofHeadCommit("abc123").scheme()).contains(RevisionScheme.HEAD_COMMIT);
            assertThat(SignalRevision.ofContentDigest("x").scheme()).contains(RevisionScheme.CONTENT_DIGEST);
            assertThat(SignalRevision.ofTerminalState("CLOSED").scheme()).contains(RevisionScheme.TERMINAL_STATE);
            assertThat(SignalRevision.ofRunId(UUID.randomUUID()).scheme()).contains(RevisionScheme.RUN_ID);
        }

        @Test
        void shouldGiveEveryRequestedRunItsOwnIdentity() {
            assertThat(SignalRevision.ofRunId(UUID.randomUUID()))
                    .isNotEqualTo(SignalRevision.ofRunId(UUID.randomUUID()));
        }

        @Test
        void shouldRejectAColon() {
            assertThatThrownBy(() -> new SignalRevision("sha~ab:cd")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldFitTheLedgerColumn() {
            assertThat(SignalRevision.ofRunId(UUID.randomUUID()).value().length())
                    .isLessThanOrEqualTo(128);
            assertThat(SignalRevision.ofContentDigest("a", "b").value().length())
                    .isLessThanOrEqualTo(128);
        }

        @Test
        void shouldReadBackTheEventIdItWasMintedFrom() {
            assertThat(SignalRevision.ofEventId(42L).eventId()).contains(42L);
        }

        @Test
        void shouldReportNoEventIdForAnotherScheme() {
            assertThat(SignalRevision.ofHeadCommit("abc123").eventId()).isEmpty();
        }

        @Test
        void shouldReportNoEventIdRatherThanThrowForAPersistedRowTheConstructorNoLongerWrites() {
            // The column only enforces the grammar, not the per-scheme suffix, so a row written before
            // eventId() existed — or edited by hand — can carry a non-numeric tail under this prefix.
            SignalRevision fromColumn = new SignalRevision("event~not-a-number");

            assertThat(fromColumn.eventId()).isEmpty();
        }
    }

    @Nested
    class Keys {

        @Test
        void shouldReadTheArtifactKindOffTheSignalSoTheTwoCannotDisagree() {
            SignalKey key =
                    new SignalKey(7L, 42L, SignalName.of("scm.issue.opened"), SignalRevision.ofContentDigest("t", "b"));

            assertThat(key.artifactKind()).isEqualTo(ArtifactKind.of("scm.issue"));
        }
    }
}
